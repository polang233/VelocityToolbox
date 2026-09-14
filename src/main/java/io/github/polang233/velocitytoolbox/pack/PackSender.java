package io.github.polang233.velocitytoolbox.pack;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.polang233.velocitytoolbox.lang.Lang;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Owns only this plugin's offers. All session changes are serialized here.
 */
public final class PackSender implements AutoCloseable {
    private final Object plugin;
    private final ProxyServer proxy;
    private final PackService host;
    private final Lang lang;
    private final Logger logger;
    private PackRules rules = PackRules.disabled();
    private final Map<UUID, Session> sessions = new HashMap<>();

    private static final class Offer {
        final PackRules.Choice choice;
        final UUID id = UUID.randomUUID();
        String state = "waiting";
        boolean sent;
        ScheduledTask timeout;

        Offer(PackRules.Choice choice) {
            this.choice = choice;
        }

        void cancel() {
            if (timeout != null) {
                timeout.cancel();
                timeout = null;
            }
        }
    }

    private static final class Session {
        final Player player;
        String server = "";
        long revision;
        String backendServer = "";
        PackRules.Selection selection;
        final List<Offer> offers = new ArrayList<>();
        ScheduledTask delay;

        Session(Player player) {
            this.player = player;
        }
    }

    public PackSender(Object plugin, ProxyServer proxy, PackService host, Lang lang, Logger logger) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.host = host;
        this.lang = lang;
        this.logger = logger;
        proxy.getEventManager().register(plugin, this);
    }

    public synchronized PackRules rules() {
        return rules;
    }

    public synchronized void apply(PackRules next) {
        rules = next;
        if (!next.enabled()) {
            for (Session session : sessions.values()) clear(session, true);
            sessions.clear();
            return;
        }
        for (Player player : proxy.getAllPlayers()) refresh(player, false);
    }

    @Subscribe
    public synchronized void connected(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        refresh(player, false);
    }

    @Subscribe
    public synchronized void backend(ServerResourcePackSendEvent event) {
        Player player = event.getServerConnection().getPlayer();
        if (!rules.enabled() || modern(player) || !event.getResult().isAllowed()) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player) {
            if (session != null) clear(session, false);
            session = new Session(player);
            sessions.put(player.getUniqueId(), session);
        }
        session.backendServer = event.getServerConnection().getServerInfo().getName();
        clear(session, false);
        session.selection = null;
    }

    @Subscribe
    public synchronized void disconnected(DisconnectEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.player == event.getPlayer()) {
            clear(session, false);
            sessions.remove(event.getPlayer().getUniqueId());
        }
    }

    public synchronized boolean resend(Player player) {
        if (!rules.enabled() || player.getCurrentServer().isEmpty()) return false;
        refresh(player, true);
        return true;
    }

    private void refresh(Player player, boolean force) {
        if (!rules.enabled() || !player.isActive() || player.getCurrentServer().isEmpty()) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player) {
            if (session != null) clear(session, false);
            session = new Session(player);
            sessions.put(player.getUniqueId(), session);
        }
        String server = player.getCurrentServer().orElseThrow().getServerInfo().getName();
        session.server = server;
        if (force || !server.equals(session.backendServer)) session.backendServer = "";
        if (!session.backendServer.isEmpty()) return;
        var selected = rules.select(server, player.getProtocolVersion(), player::hasPermission);
        if (prepare(session, selected, force) && !selected.skipped().isEmpty() && !selected.required())
            lang.send(player, "delivery.skipped", Lang.ph("packs", String.join(", ", selected.skipped())));
        if (session.delay != null) session.delay.cancel();
        long revision = ++session.revision;
        Session current = session;
        session.delay = proxy.getScheduler().buildTask(plugin, () -> {
            synchronized (PackSender.this) {
                if (!current(current) || current.revision != revision || !current.backendServer.isEmpty()) return;
                current.delay = null;
                String actual = player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
                if (!actual.equals(current.server)) return;
                send(current);
            }
        }).delay(rules.delay(), TimeUnit.MILLISECONDS).schedule();
    }

    private void send(Session session) {
        Player player = session.player;
        PackRules.Selection next = rules.select(session.server, player.getProtocolVersion(), player::hasPermission);
        if (next.blocked()) {
            session.selection = next;
            clear(session, true);
            fail(player, true, String.join(", ", next.skipped()), "unavailable");
            return;
        }
        boolean changed = prepare(session, next, false);
        if (changed && !next.skipped().isEmpty())
            lang.send(player, "delivery.skipped", Lang.ph("packs", String.join(", ", next.skipped())));
        for (int i = session.offers.size(); i < next.packs().size(); i++) {
            Offer offer = new Offer(next.packs().get(i));
            session.offers.add(offer);
            if (offer.choice.file().local() && !host.enabled()) {
                offer.state = "unavailable";
                fail(player, next.required(), offer.choice.name(), offer.state);
                if (next.required()) break;
                continue;
            }
            try {
                var info = proxy.createResourcePackBuilder(offer.choice.file().url())
                        .setHash(HexFormat.of().parseHex(offer.choice.file().sha1()))
                        .setId(offer.id).setPrompt(next.prompt()).setShouldForce(next.required()).build();
                offer.sent = true;
                // Random offer IDs distinguish a resend from a late response to the same content.
                offer.timeout = proxy.getScheduler().buildTask(plugin, () -> expire(session, offer))
                        .delay(rules.timeout(), TimeUnit.MILLISECONDS).schedule();
                player.sendResourcePackOffer(info);
            } catch (RuntimeException e) {
                offer.cancel();
                offer.state = "failed";
                logger.warn("Could not send resource pack {}", offer.choice.name(), e);
                fail(player, next.required(), offer.choice.name(), offer.state);
                if (next.required()) break;
            }
        }
    }

    private boolean prepare(Session session, PackRules.Selection next, boolean force) {
        if (!force && next.equals(session.selection)) return false;
        int keep = 0;
        if (!force && session.selection != null && next.required() == session.selection.required()
                && next.prompt().equals(session.selection.prompt()) && !next.blocked()) {
            while (keep < next.packs().size() && keep < session.offers.size()
                    && next.packs().get(keep).equals(session.offers.get(keep).choice)) keep++;
        }
        for (int i = session.offers.size() - 1; i >= keep; i--)
            remove(session, session.offers.remove(i), true);
        session.selection = next;
        return true;
    }

    private synchronized void expire(Session session, Offer offer) {
        if (!current(session) || !session.offers.contains(offer) || terminal(offer.state)) return;
        offer.state = "timeout";
        offer.cancel();
        fail(session.player, session.selection.required(), offer.choice.name(), offer.state);
    }

    @Subscribe
    public synchronized void status(PlayerResourcePackStatusEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.player != event.getPlayer() || session.selection == null) return;
        UUID id = event.getPackId();
        if (id == null && event.getPackInfo() != null) id = event.getPackInfo().getId();
        if (id == null) return;
        for (Offer offer : session.offers) {
            if (!offer.id.equals(id) || !offer.sent || terminal(offer.state)) continue;
            String state = switch (event.getStatus().name()) {
                case "ACCEPTED" -> "accepted";
                case "DOWNLOADED" -> "downloaded";
                case "SUCCESSFUL", "SUCCESSFULLY_LOADED" -> "loaded";
                case "DECLINED" -> "declined";
                case "FAILED_DOWNLOAD", "FAILED_RELOAD", "INVALID_URL", "DISCARDED" -> "failed";
                default -> null;
            };
            if (state == null) return;
            if (state.equals("accepted") && offer.state.equals("downloaded")) return;
            offer.state = state;
            if (terminal(state)) offer.cancel();
            if (!state.equals("loaded") && terminal(state))
                fail(session.player, session.selection.required(), offer.choice.name(), state);
            return;
        }
    }

    private void fail(Player player, boolean required, String name, String reason) {
        var message = lang.get("delivery.failure", Lang.ph("pack", name),
                Lang.ph("reason", lang.plain("delivery.state." + reason)));
        if (required) player.disconnect(message);
        else lang.send(player, message);
    }

    public synchronized void show(Player player, com.velocitypowered.api.command.CommandSource source) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player) {
            lang.send(source, "delivery.no-state", Lang.ph("player", player.getUsername()));
            return;
        }
        lang.send(source, "delivery.status", Lang.ph("player", player.getUsername()), Lang.ph("server", session.server));
        if (!session.backendServer.isEmpty()) lang.send(source, "delivery.backend");
        if (session.delay != null) lang.send(source, "delivery.waiting");
        if (session.selection != null && !session.selection.skipped().isEmpty())
            lang.send(source, "delivery.skipped", Lang.ph("packs", String.join(", ", session.selection.skipped())));
        if (session.offers.isEmpty()) lang.send(source, "delivery.empty");
        for (Offer offer : session.offers)
            lang.send(source, "delivery.item", Lang.ph("pack", offer.choice.name()),
                    Lang.ph("state", lang.plain("delivery.state." + offer.state)));
    }

    private boolean current(Session session) {
        return rules.enabled() && session.player.isActive() && sessions.get(session.player.getUniqueId()) == session;
    }

    private static boolean modern(Player player) {
        return player.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_20_3) >= 0;
    }

    private static boolean terminal(String state) {
        return Set.of("loaded", "failed", "declined", "timeout", "unavailable").contains(state);
    }

    private void remove(Session session, Offer offer, boolean remove) {
        offer.cancel();
        if (remove && offer.sent && modern(session.player) && session.player.isActive())
            session.player.removeResourcePacks(offer.id);
    }

    private void clear(Session session, boolean remove) {
        session.revision++;
        if (session.delay != null) {
            session.delay.cancel();
            session.delay = null;
        }
        for (Offer offer : session.offers) remove(session, offer, remove);
        session.offers.clear();
    }

    @Override
    public synchronized void close() {
        for (Session session : sessions.values()) clear(session, true);
        sessions.clear();
        rules = PackRules.disabled();
        proxy.getEventManager().unregisterListener(plugin, this);
    }
}
