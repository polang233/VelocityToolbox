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
import io.github.polang233.velocitytoolbox.pack.config.HostLimits;
import io.github.polang233.velocitytoolbox.pack.config.PackRules;
import io.github.polang233.velocitytoolbox.pack.host.PackService;
import io.github.polang233.velocitytoolbox.pack.http.DownloadTickets;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 管理 VTB 的玩家请求和回执；同步更新会话，切服时撤销旧任务。
 */
public final class PackSender implements AutoCloseable {
    private static final int PLAYERS_PER_BATCH = 5;
    private static final long BATCH_INTERVAL_MS = 1000;
    private static final long SWITCH_GAP_NANOS = TimeUnit.SECONDS.toNanos(1);
    private final Object plugin;
    private final ProxyServer proxy;
    private final PackService host;
    private final Lang lang;
    private final Logger logger;
    private final LongSupplier clock;
    private PackRules rules = PackRules.disabled();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private Batch batch;

    private record BatchPlayer(Player player, Session session, long revision, String server) {}

    private static final class Batch {
        final CommandSource source;
        final ArrayDeque<BatchPlayer> players;
        int scheduled;
        int empty;
        int skipped;
        ScheduledTask task;

        Batch(CommandSource source, Collection<BatchPlayer> players) {
            this.source = source;
            this.players = new ArrayDeque<>(players);
        }
    }

    private static final class Offer {
        final PackRules.Choice choice;
        final UUID id = UUID.randomUUID();
        String state = "waiting";
        boolean sent;
        boolean downloadFailed;
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
        String pausedServer;
        PackRules.Selection selection;
        final List<Offer> offers = new ArrayList<>();
        ScheduledTask delay;
        boolean withdrawn;
        long withdrawnAt;

        Session(Player player) {
            this.player = player;
        }
    }

    public PackSender(Object plugin, ProxyServer proxy, PackService host, Lang lang, Logger logger) {
        this(plugin, proxy, host, lang, logger, System::nanoTime);
    }

    public PackSender(Object plugin, ProxyServer proxy, PackService host, Lang lang, Logger logger, LongSupplier clock) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.host = host;
        this.lang = lang;
        this.logger = logger;
        this.clock = clock;
        proxy.getEventManager().register(plugin, this);
    }

    public synchronized PackRules rules() {
        return rules;
    }

    public synchronized void apply(PackRules next) {
        cancelBatch(next.enabled() ? "pack.batch.reloaded" : "pack.batch.cancelled");
        rules = next;
        if (!next.enabled()) {
            for (Session session : sessions.values()) clear(session, true);
            sessions.values().removeIf(session -> session.pausedServer == null);
            return;
        }
        var waiting = new ArrayList<Session>();
        for (Player player : proxy.getAllPlayers()) {
            if (refresh(player, false, true)) waiting.add(sessions.get(player.getUniqueId()));
        }
        // 所有需要换包的玩家共用起始间隔，避免已撤包和刚撤包两组挤进同一秒。
        long wait = rules.delay();
        for (Session session : waiting) wait = Math.max(wait, switchWait(session));
        for (int i = 0; i < waiting.size(); i++)
            schedule(waiting.get(i), wait + i / PLAYERS_PER_BATCH * BATCH_INTERVAL_MS);
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
        Session session = session(player);
        session.backendServer = event.getServerConnection().getServerInfo().getName();
        clear(session, false);
        session.selection = null;
    }

    /** 取得玩家当前连接的会话；同 UUID 重连时丢弃旧连接的会话。 */
    private Session session(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.player == player) return session;
        if (session != null) clear(session, false);
        session = new Session(player);
        sessions.put(player.getUniqueId(), session);
        return session;
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

    public enum RemoveResult { REMOVED, EMPTY, LEGACY, OFFLINE }
    public record RemovalSummary(int removed, int empty, int legacy, int skipped) {}

    /** 先暂停并清空当前请求，再撤客户端包；保持当前子服暂停，避免 reload 立即补发。 */
    public synchronized RemoveResult remove(Player player) {
        if (!player.isActive() || player.getCurrentServer().isEmpty()) return RemoveResult.OFFLINE;
        Session session = session(player);
        boolean sent = session.offers.stream().anyMatch(offer -> offer.sent);
        boolean notify = session.pausedServer == null;
        session.server = server(player);
        session.pausedServer = session.server;
        session.selection = null;
        session.retries = 0;
        clear(session, true);
        if (notify) lang.send(player, sent && modern(player)
                ? "pack.delivery.removed-notice" : "pack.delivery.paused-notice");
        return !sent ? RemoveResult.EMPTY : modern(player) ? RemoveResult.REMOVED : RemoveResult.LEGACY;
    }

    public synchronized RemovalSummary removeAll() {
        cancelBatch();
        int removed = 0, empty = 0, legacy = 0, skipped = 0;
        for (Player player : proxy.getAllPlayers()) {
            switch (remove(player)) {
                case REMOVED -> removed++;
                case EMPTY -> empty++;
                case LEGACY -> legacy++;
                case OFFLINE -> skipped++;
            }
        }
        return new RemovalSummary(removed, empty, legacy, skipped);
    }

    public synchronized ResendResult resend(Player player) {
        if (!rules.enabled() || !player.isActive() || player.getCurrentServer().isEmpty()) return ResendResult.DISABLED;
        refresh(player, true);
        if (keeping(sessions.get(player.getUniqueId()), player))
            return ResendResult.EMPTY;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player || session.selection == null) return ResendResult.DISABLED;
        return session.selection.packs().isEmpty() ? ResendResult.EMPTY : ResendResult.SCHEDULED;
    }

    /** 先作废所有旧请求，再分批下发；重载可按最新规则接管尚未发出的请求。 */
    public synchronized boolean resendAll(CommandSource source) {
        if (!rules.enabled()) {
            lang.send(source, "pack.delivery.unavailable");
            return false;
        }
        if (batch != null) {
            lang.send(source, "pack.batch.busy");
            return false;
        }
        var players = new ArrayList<BatchPlayer>();
        long wait = 0;
        for (Player player : proxy.getAllPlayers()) {
            refresh(player, true, true);
            Session session = sessions.get(player.getUniqueId());
            players.add(new BatchPlayer(player, session, session == null ? 0 : session.revision, server(player)));
            if (session != null) wait = Math.max(wait, switchWait(session));
        }
        Batch next = new Batch(source, players);
        if (next.players.isEmpty()) {
            lang.send(source, "pack.batch.empty");
            return false;
        }
        batch = next;
        lang.send(source, "pack.batch.started", Lang.ph("count", next.players.size()));
        scheduleBatch(next, Math.max(0, wait - rules.delay()));
        return true;
    }

    private void scheduleBatch(Batch next, long delay) {
        next.task = proxy.getScheduler().buildTask(plugin, () -> runBatch(next))
                .delay(delay, TimeUnit.MILLISECONDS).schedule();
    }

    private synchronized void runBatch(Batch next) {
        if (batch != next) return;
        next.task = null;
        for (int i = 0; i < PLAYERS_PER_BATCH && !next.players.isEmpty(); i++) {
            BatchPlayer entry = next.players.removeFirst();
            Player player = entry.player();
            // 排队后已经切服、重发或开始重试的玩家，由更新的请求接管。
            if (sessions.get(player.getUniqueId()) != entry.session()
                    || entry.session() != null && entry.session().revision != entry.revision()
                    || !server(player).equals(entry.server())) {
                next.skipped++;
                continue;
            }
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
        } else scheduleBatch(next, BATCH_INTERVAL_MS);
    }

    private void cancelBatch() {
        cancelBatch("pack.batch.cancelled");
    }

    private void cancelBatch(String message) {
        if (batch == null) return;
        Batch previous = batch;
        batch = null;
        if (previous.task != null) previous.task.cancel();
        lang.send(previous.source, message, Lang.ph("remaining", previous.players.size()));
    }

    private void refresh(Player player, boolean force) {
        refresh(player, force, false);
    }

    private boolean refresh(Player player, boolean force, boolean enqueueOnly) {
        if (!rules.enabled() || !player.isActive() || player.getCurrentServer().isEmpty()) return false;
        Session session = session(player);
        String server = server(player);
        session.server = server;
        if (session.pausedServer != null) {
            if (force || !server.equals(session.pausedServer)) session.pausedServer = null;
            else return false;
        }
        if (force || !server.equals(session.backendServer)) session.backendServer = "";
        if (!session.backendServer.isEmpty()) return false;
        if (keeping(session, player)) {
            // 玩家已离开原子服，取消尚未发出的延迟请求。
            session.revision++;
            cancelDelay(session);
            session.offers.removeIf(offer -> {
                if (offer.sent) return false;
                offer.cancel();
                return true;
            });
            session.selection = new PackRules.Selection(
                    session.offers.stream().map(offer -> offer.choice).toList(), List.of());
            return false;
        }
        var selected = rules.select(server, player.getProtocolVersion(), player::hasPermission);
        if (enqueueOnly && force) {
            session.revision++;
            cancelDelay(session);
        }
        boolean changed = prepare(session, selected, force && (enqueueOnly || session.delay == null));
        if (changed && !selected.skipped().isEmpty())
            lang.send(player, "pack.delivery.no-match");
        // 相同配置的重载不提前待重试请求，也不打断已有的分批下发。
        if (!changed && session.delay != null) return false;
        if (session.offers.size() >= selected.packs().size()) {
            cancelDelay(session);
            session.revision++;
            return false;
        }
        if (!enqueueOnly) schedule(session, Math.max(rules.delay(), switchWait(session)));
        return true;
    }

    /** 当前子服是否只保留、不下发：纯保留，或配了 packs 但本次连接已下发过 VTB 包。 */
    private boolean keeping(Session session, Player player) {
        String server = server(player);
        if (!rules.keepExisting(server)) return false;
        return rules.select(server, player.getProtocolVersion(), player::hasPermission).packs().isEmpty()
                || session != null && session.offers.stream().anyMatch(offer -> offer.sent);
    }

    private void schedule(Session session, long delay) {
        cancelDelay(session);
        long revision = ++session.revision;
        if (session.selection.packs().isEmpty()) return;
        delay = Math.max(delay, switchWait(session));
        session.delay = proxy.getScheduler().buildTask(plugin, () -> {
            synchronized (PackSender.this) {
                if (!current(session) || session.pausedServer != null || session.revision != revision
                        || !session.backendServer.isEmpty()) return;
                session.delay = null;
                String actual = server(session.player);
                if (actual.equals(session.server)) send(session);
            }
        }).delay(delay, TimeUnit.MILLISECONDS).schedule();
    }

    private long switchWait(Session session) {
        if (!session.withdrawn) return 0;
        long remaining = SWITCH_GAP_NANOS - Math.max(0, clock.getAsLong() - session.withdrawnAt);
        return remaining <= 0 ? 0 : (remaining + 999_999) / 1_000_000;
    }

    private void send(Session session) {
        Player player = session.player;
        PackRules.Selection next = rules.select(session.server, player.getProtocolVersion(), player::hasPermission);
        boolean changed = prepare(session, next, false);
        long revision = session.revision;
        if (changed && !next.skipped().isEmpty())
            lang.send(player, "pack.delivery.no-match");
        if (changed && switchWait(session) > 0) {
            schedule(session, Math.max(rules.delay(), switchWait(session)));
            return;
        }
        for (int i = session.offers.size(); i < next.packs().size(); i++) {
            if (!current(session) || session.revision != revision) return;
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
                        fail(player, offer.choice.required(), offer.choice.name(), offer.state);
                        if (offer.choice.required()) break;
                        continue;
                    }
                    url = offer.ticket.url();
                }
                // required 统一由当前有效的 VTB 请求校验，撤掉的旧包不能触发原生断开。
                var info = proxy.createResourcePackBuilder(url)
                        .setHash(HexFormat.of().parseHex(offer.choice.file().sha1()))
                        .setId(offer.id).setPrompt(offer.choice.prompt()).setShouldForce(false).build();
                offer.sent = true;
                // 每次下发使用新 ID，避免旧回执更新重发后的状态。
                offer.timeout = proxy.getScheduler().buildTask(plugin, () -> expire(session, offer))
                        .delay(rules.timeout(), TimeUnit.MILLISECONDS).schedule();
                player.sendResourcePackOffer(info);
                // 回执可能同步触发重试、切服或停用，不能继续发送已经被替换的后续包。
                if (!current(session) || session.revision != revision || !session.offers.contains(offer)) return;
            } catch (RuntimeException e) {
                if (!current(session) || session.revision != revision || !session.offers.contains(offer)) return;
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
        if (!force && next.equals(session.selection) && session.offers.stream().noneMatch(PackSender::recoverable))
            return false;
        session.retries = 0;
        int keep = 0;
        if (!force && session.selection != null) {
            while (keep < next.packs().size() && keep < session.offers.size()
                    && next.packs().get(keep).equals(session.offers.get(keep).choice)
                    && !recoverable(session.offers.get(keep))) keep++;
        }
        session.selection = next;
        removeFrom(session, keep, true);
        return true;
    }

    /** 重新进入分配子服或重载时允许恢复下载失败，保留成功、进行中和明确拒绝的请求。 */
    private static boolean recoverable(Offer offer) {
        return offer.choice.file().local() && (offer.state.equals("busy") || offer.state.equals("timeout")
                || offer.state.equals("failed") && offer.downloadFailed);
    }

    private synchronized void expire(Session session, Offer offer) {
        if (!synchronizeServer(session) || !session.offers.contains(offer) || terminal(offer.state)) return;
        if (limited(offer) && retry(session, offer)) return;
        String detail = switch (offer.state) {
            case "downloaded" -> "APPLY_TIMEOUT";
            case "accepted" -> "COMPLETION_TIMEOUT";
            default -> "RESPONSE_TIMEOUT";
        };
        offer.state = limited(offer) ? "busy" : "timeout";
        offer.cancel();
        fail(session.player, offer.choice.required(), offer.choice.name(), offer.state, detail, diagnostic(offer));
    }

    @Subscribe
    public synchronized void status(PlayerResourcePackStatusEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.player != event.getPlayer() || !synchronizeServer(session)
                || session.selection == null) return;
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
            offer.downloadFailed = event.getStatus().name().equals("FAILED_DOWNLOAD");
            if (offer.downloadFailed && offer.choice.file().local()) {
                if (retry(session, offer)) return;
                if (limited(offer)) state = "busy";
            }
            offer.state = state;
            if (terminal(state)) offer.cancel();
            if (!state.equals("loaded") && terminal(state))
                fail(session.player, offer.choice.required(), offer.choice.name(), state, event.getStatus().name(), diagnostic(offer));
            return;
        }
    }

    private static boolean limited(Offer offer) { return offer.ticket != null && offer.ticket.limited(); }
    private static DownloadTickets.Diagnostic diagnostic(Offer offer) {
        return offer.ticket == null ? null : offer.ticket.diagnostic();
    }

    /** 自托管下载失败或明确过载时，撤下失败包及后续叠加项，保留前缀；最多两次。 */
    private boolean retry(Session session, Offer offer) {
        // keep-existing 子服只重试本子服分配的包，不撤下保留的包。
        if (rules.keepExisting(session.server) && !rules.select(session.server, session.player.getProtocolVersion(),
                session.player::hasPermission).packs().contains(offer.choice)) return false;
        if (session.retries >= HostLimits.RETRIES) return false;
        int start = session.offers.indexOf(offer);
        if (start < 0) return false;
        session.retries++;
        boolean busy = limited(offer) || !offer.downloadFailed;
        logger.warn("Resource pack retry: player={}, server={}, pack={}, attempt={}, cause={}, http={}",
                session.player.getUsername(), session.server, offer.choice.name(), session.retries,
                busy ? "host-busy" : "FAILED_DOWNLOAD", diagnostic(offer));
        removeFrom(session, start, true);
        lang.send(session.player, busy ? "pack.delivery.retry" : "pack.delivery.retry-download",
                Lang.ph("attempt", session.retries));
        schedule(session, HostLimits.RETRY_SECONDS * 1000L * session.retries);
        return true;
    }

    private void fail(Player player, boolean required, String name, String reason) {
        fail(player, required, name, reason, reason, null);
    }

    private void fail(Player player, boolean required, String name, String reason, String detail, DownloadTickets.Diagnostic http) {
        String server = player.getCurrentServer().map(connection -> connection.getServerInfo().getName())
                .orElse("disconnected");
        String cause = lang.plain(failureReason(reason, detail, http));
        logger.warn("Resource pack failure: player={} ({}), server={}, pack={}, reason={}, status={}, cause={}, http={}",
                player.getUsername(), player.getUniqueId(), server, name, reason, detail, cause, http);
        var message = lang.get("pack.delivery.not-applied", Lang.ph("reason", cause));
        if (required) player.disconnect(message);
        else lang.send(player, message);
    }

    private static String failureReason(String reason, String detail, DownloadTickets.Diagnostic http) {
        boolean download = detail.equals("FAILED_DOWNLOAD") || reason.equals("timeout") || reason.equals("busy");
        if (download && !detail.equals("APPLY_TIMEOUT") && http != null) {
            String observed = switch (http.status()) {
                case 429 -> "rate";
                case 503 -> "busy";
                case 404 -> "missing";
                case 409 -> "changed";
                default -> http.status() >= 400 ? "rejected" : null;
            };
            if (observed == null) observed = switch (http.stage()) {
                case TIMED_OUT -> "transfer-timeout";
                case INTERRUPTED -> "connection";
                case FILE_ERROR -> "read";
                case STOPPED -> "stopped";
                default -> null;
            };
            if (observed != null) return observed.equals("busy") ? "pack.delivery.state.busy" : "pack.delivery.failure-reason." + observed;
        }
        if (reason.equals("busy") && (http == null || http.stage() == DownloadTickets.Stage.NOT_REQUESTED))
            return "pack.delivery.state.busy";
        return switch (detail) {
            case "FAILED_DOWNLOAD" -> "pack.delivery.failure-reason.download-unknown";
            case "FAILED_RELOAD" -> "pack.delivery.failure-reason.reload";
            case "INVALID_URL" -> "pack.delivery.failure-reason.url";
            case "DISCARDED" -> "pack.delivery.failure-reason.discarded";
            case "failed" -> "pack.delivery.failure-reason.send";
            case "APPLY_TIMEOUT" -> "pack.delivery.failure-reason.apply-timeout";
            case "COMPLETION_TIMEOUT" -> "pack.delivery.failure-reason.completion-timeout";
            case "RESPONSE_TIMEOUT" -> "pack.delivery.failure-reason.response-timeout";
            default -> "pack.delivery.state." + reason;
        };
    }

    public synchronized void show(Player player, CommandSource source) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player) {
            lang.send(source, "pack.delivery.no-state", Lang.ph("player", player.getUsername()));
            return;
        }
        lang.send(source, "pack.delivery.player-title", Lang.ph("player", player.getUsername()), Lang.ph("server", session.server));
        if (session.pausedServer != null) lang.send(source, "pack.delivery.paused");
        if (keeping(session, player)) lang.send(source, "pack.delivery.kept");
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

    private static String server(Player player) {
        return player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
    }

    /** 实际切服可能早于 PostConnect 回调取得锁；先更新分配，再判断旧回执或超时。 */
    private boolean synchronizeServer(Session session) {
        if (!current(session)) return false;
        String actual = server(session.player);
        if (actual.isEmpty()) return false;
        if (!actual.equals(session.server)) refresh(session.player, false);
        return current(session);
    }

    private static boolean modern(Player player) {
        return player.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_20_3) >= 0;
    }

    private static final Set<String> TERMINAL = Set.of("loaded", "failed", "declined", "timeout", "unavailable", "busy");

    private static boolean terminal(String state) {
        return TERMINAL.contains(state);
    }

    private void retire(Session session, Offer offer, boolean remove) {
        offer.cancel();
        if (offer.sent) {
            session.withdrawn = true;
            session.withdrawnAt = clock.getAsLong();
        }
        if (remove && offer.sent && modern(session.player) && session.player.isActive())
            session.player.removeResourcePacks(offer.id);
    }

    private void removeFrom(Session session, int start, boolean withdraw) {
        var retired = new ArrayList<>(session.offers.subList(start, session.offers.size()));
        session.offers.subList(start, session.offers.size()).clear();
        for (int i = retired.size() - 1; i >= 0; i--) retire(session, retired.get(i), withdraw);
    }

    private static void cancelDelay(Session session) {
        if (session.delay != null) session.delay.cancel();
        session.delay = null;
    }

    private void clear(Session session, boolean remove) {
        session.revision++;
        cancelDelay(session);
        removeFrom(session, 0, remove);
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
