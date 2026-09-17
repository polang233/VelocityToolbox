package io.github.polang233.velocitytoolbox.pack.delivery;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.polang233.velocitytoolbox.lang.Lang;
import io.github.polang233.velocitytoolbox.pack.config.PackRules;
import io.github.polang233.velocitytoolbox.pack.host.PackService;
import io.github.polang233.velocitytoolbox.pack.http.DownloadTickets;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 管理 VTB 的玩家请求和回执；同步更新会话，切服时撤销旧任务。
 */
public final class PackSender implements AutoCloseable {
    private final Object plugin;
    private final ProxyServer proxy;
    private final PackService host;
    private final Lang lang;
    private final Logger logger;
    private PackRules rules = PackRules.disabled();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private Batch batch;

    private static final class Batch {
        final CommandSource source;
        final ArrayDeque<Player> players;
        int scheduled;
        int empty;
        int skipped;
        ScheduledTask task;

        Batch(CommandSource source, java.util.Collection<Player> players) {
            this.source = source;
            this.players = new ArrayDeque<>(players);
        }
    }

    private static final class Offer {
        final PackRules.Choice choice;
        final UUID id = UUID.randomUUID();
        String state = "waiting";
        boolean sent;
        ScheduledTask timeout;
        DownloadTickets.Ticket ticket;

        Offer(PackRules.Choice choice) {
            this.choice = choice;
        }

        void cancel() {
            if (ticket != null) ticket.close();
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
        int retries;
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
        cancelBatch();
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

    public enum ResendResult { DISABLED, EMPTY, SCHEDULED }

    public synchronized ResendResult resend(Player player) {
        if (!rules.enabled() || !player.isActive() || player.getCurrentServer().isEmpty()) return ResendResult.DISABLED;
        refresh(player, true);
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player || session.selection == null) return ResendResult.DISABLED;
        return session.selection.packs().isEmpty() ? ResendResult.EMPTY : ResendResult.SCHEDULED;
    }

    /** 全部玩家使用一个分批任务；重载或停用会取消剩余队列。 */
    public synchronized boolean resendAll(CommandSource source) {
        if (!rules.enabled()) {
            lang.send(source, "pack.delivery.unavailable");
            return false;
        }
        if (batch != null) {
            lang.send(source, "pack.batch.busy");
            return false;
        }
        Batch next = new Batch(source, proxy.getAllPlayers());
        if (next.players.isEmpty()) {
            lang.send(source, "pack.batch.empty");
            return false;
        }
        batch = next;
        lang.send(source, "pack.batch.started", Lang.ph("count", next.players.size()));
        scheduleBatch(next, 0);
        return true;
    }

    private void scheduleBatch(Batch next, long delay) {
        next.task = proxy.getScheduler().buildTask(plugin, () -> runBatch(next))
                .delay(delay, TimeUnit.MILLISECONDS).schedule();
    }

    private synchronized void runBatch(Batch next) {
        if (batch != next) return;
        next.task = null;
        for (int i = 0; i < 5 && !next.players.isEmpty(); i++) {
            Player player = next.players.removeFirst();
            try {
                switch (resend(player)) {
                    case SCHEDULED -> next.scheduled++;
                    case EMPTY -> next.empty++;
                    case DISABLED -> next.skipped++;
                }
            } catch (RuntimeException error) {
                next.skipped++;
                logger.warn("Could not resend resource packs to {}", player.getUsername(), error);
            }
        }
        if (next.players.isEmpty()) {
            batch = null;
            lang.send(next.source, "pack.batch.finished", Lang.ph("scheduled", next.scheduled),
                    Lang.ph("empty", next.empty), Lang.ph("skipped", next.skipped));
        } else scheduleBatch(next, 1000);
    }

    private void cancelBatch() {
        if (batch == null) return;
        if (batch.task != null) batch.task.cancel();
        lang.send(batch.source, "pack.batch.cancelled", Lang.ph("remaining", batch.players.size()));
        batch = null;
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
        if (prepare(session, selected, force) && !selected.skipped().isEmpty())
            lang.send(player, "pack.delivery.skipped", Lang.ph("packs", String.join(", ", selected.skipped())));
        schedule(session, rules.delay());
    }

    private void schedule(Session session, long delay) {
        if (session.delay != null) session.delay.cancel();
        session.delay = null;
        long revision = ++session.revision;
        if (session.selection.packs().isEmpty()) return;
        session.delay = proxy.getScheduler().buildTask(plugin, () -> {
            synchronized (PackSender.this) {
                if (!current(session) || session.revision != revision || !session.backendServer.isEmpty()) return;
                session.delay = null;
                String actual = session.player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
                if (actual.equals(session.server)) send(session);
            }
        }).delay(delay, TimeUnit.MILLISECONDS).schedule();
    }

    private void send(Session session) {
        Player player = session.player;
        PackRules.Selection next = rules.select(session.server, player.getProtocolVersion(), player::hasPermission);
        boolean changed = prepare(session, next, false);
        if (changed && !next.skipped().isEmpty())
            lang.send(player, "pack.delivery.skipped", Lang.ph("packs", String.join(", ", next.skipped())));
        for (int i = session.offers.size(); i < next.packs().size(); i++) {
            Offer offer = new Offer(next.packs().get(i));
            session.offers.add(offer);
            if (offer.choice.file().local() && !host.enabled()) {
                offer.state = "unavailable";
                fail(player, offer.choice.required(), offer.choice.name(), offer.state);
                if (offer.choice.required()) break;
                continue;
            }
            try {
                String url = offer.choice.file().url();
                if (offer.choice.file().local()) {
                    offer.ticket = host.ticket(url, rules.timeout() + 60000);
                    if (offer.ticket == null) {
                        if (retry(session, offer)) break;
                        offer.state = "busy";
                        fail(player, offer.choice.required(), offer.choice.name(), "busy");
                        if (offer.choice.required()) break;
                        continue;
                    }
                    url = offer.ticket.url();
                }
                // 旧客户端由 VTB 按当前请求校验，避免代理因已取消的必需包回执误踢。
                // 自托管重试也由 VTB 校验，避免客户端在 HTTP 限流时自行断开。
                boolean nativeForce = offer.choice.required()
                        && player.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_17) >= 0
                        && (!offer.choice.file().local() || host.limits().retries() == 0);
                var info = proxy.createResourcePackBuilder(url)
                        .setHash(HexFormat.of().parseHex(offer.choice.file().sha1()))
                        .setId(offer.id).setPrompt(offer.choice.prompt()).setShouldForce(nativeForce).build();
                offer.sent = true;
                // 每次下发使用新 ID，避免旧回执更新重发后的状态。
                offer.timeout = proxy.getScheduler().buildTask(plugin, () -> expire(session, offer))
                        .delay(rules.timeout(), TimeUnit.MILLISECONDS).schedule();
                player.sendResourcePackOffer(info);
            } catch (RuntimeException e) {
                offer.cancel();
                offer.state = "failed";
                logger.warn("Could not send resource pack {}", offer.choice.name(), e);
                fail(player, offer.choice.required(), offer.choice.name(), offer.state);
                if (offer.choice.required()) break;
            }
        }
    }

    /** 保留未变化的前缀，按叠加顺序重新发送后续包。 */
    private boolean prepare(Session session, PackRules.Selection next, boolean force) {
        if (!force && next.equals(session.selection)) return false;
        session.retries = 0;
        int keep = 0;
        if (!force && session.selection != null) {
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
        if (limited(offer) && retry(session, offer)) return;
        offer.state = limited(offer) ? "busy" : "timeout";
        offer.cancel();
        fail(session.player, offer.choice.required(), offer.choice.name(), offer.state);
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
            if (state.equals("failed") && limited(offer)) {
                if (retry(session, offer)) return;
                state = "busy";
            }
            offer.state = state;
            if (terminal(state)) offer.cancel();
            if (!state.equals("loaded") && terminal(state))
                fail(session.player, offer.choice.required(), offer.choice.name(), state);
            return;
        }
    }

    private static boolean limited(Offer offer) { return offer.ticket != null && offer.ticket.limited(); }

    /** 仅重试本机明确记录的过载，撤下失败包及后续叠加项，保留前缀。 */
    private boolean retry(Session session, Offer offer) {
        if (session.retries >= host.limits().retries()) return false;
        session.retries++;
        int start = session.offers.indexOf(offer);
        if (start < 0) return false;
        for (int i = session.offers.size() - 1; i >= start; i--) remove(session, session.offers.remove(i), true);
        lang.send(session.player, "pack.delivery.retry", Lang.ph("attempt", session.retries));
        schedule(session, host.limits().retrySeconds() * 1000L);
        return true;
    }

    private void fail(Player player, boolean required, String name, String reason) {
        var message = lang.get("pack.delivery.failure", Lang.ph("pack", name),
                Lang.ph("reason", lang.plain("pack.delivery.state." + reason)));
        if (required) player.disconnect(message);
        else lang.send(player, message);
    }

    public synchronized void show(Player player, com.velocitypowered.api.command.CommandSource source) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player) {
            lang.send(source, "pack.delivery.no-state", Lang.ph("player", player.getUsername()));
            return;
        }
        lang.send(source, "pack.delivery.player-title", Lang.ph("player", player.getUsername()), Lang.ph("server", session.server));
        if (!session.backendServer.isEmpty()) lang.send(source, "pack.delivery.backend");
        if (session.delay != null) lang.send(source, "pack.delivery.waiting");
        if (session.selection != null && !session.selection.skipped().isEmpty())
            lang.send(source, "pack.delivery.skipped", Lang.ph("packs", String.join(", ", session.selection.skipped())));
        if (session.offers.isEmpty()) lang.send(source, "pack.delivery.empty");
        for (Offer offer : session.offers)
            lang.send(source, "pack.delivery.item", Lang.ph("pack", offer.choice.name()),
                    Lang.ph("state", lang.plain("pack.delivery.state." + offer.state)),
                    Lang.ph("required", lang.plain(offer.choice.required() ? "pack.label.required" : "pack.label.optional")));
    }

    private boolean current(Session session) {
        return rules.enabled() && session.player.isActive() && sessions.get(session.player.getUniqueId()) == session;
    }

    private static boolean modern(Player player) {
        return player.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_20_3) >= 0;
    }

    private static boolean terminal(String state) {
        return Set.of("loaded", "failed", "declined", "timeout", "unavailable", "busy").contains(state);
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
        cancelBatch();
        for (Session session : sessions.values()) clear(session, true);
        sessions.clear();
        rules = PackRules.disabled();
        proxy.getEventManager().unregisterListener(plugin, this);
    }
}
