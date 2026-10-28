package io.github.polang233.velocitytoolbox.pack.host;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import io.github.polang233.velocitytoolbox.command.VelocityToolboxCommand;
import io.github.polang233.velocitytoolbox.config.ResourceFiles;
import io.github.polang233.velocitytoolbox.pack.config.PackRules;
import io.github.polang233.velocitytoolbox.pack.delivery.PackSender;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 撤包暂停、恢复、旧回执与任务隔离，以及命令权限和批量边界。 */
final class PackRemovalTest {
    private static final String POLICY = """
            enabled: true
            settings:
              delay: 0
              timeout: 60
              required: true
            packs:
              main:
                - url: https://example.com/main.zip
                  hash: 0123456789abcdef0123456789abcdef01234567
            servers:
              default:
                packs: [main]
              passive:
                keep-existing: true
            """;

    static void run(Path root) throws Exception {
        pauseAndResume(root);
        pendingTasks(root);
        batchRemoval(root);
        bulkCommandSequences(root);
        replacementSpacing(root);
        commands(root);
    }

    private static PackRules rules(Path directory, String yaml) throws Exception {
        Path file = directory.resolve("remove-policy.yml");
        Files.writeString(file, yaml);
        return PackRules.read(ResourceFiles.loadYaml(file), List.of());
    }

    private static void reply(PackSender sender, PackDeliveryTest.Harness h, ResourcePackInfo offer, String status) {
        var event = new PlayerResourcePackStatusEvent(h.player, offer.getId(),
                PlayerResourcePackStatusEvent.Status.valueOf(status), offer);
        sender.status(event);
        check(!offer.getShouldForce(), "retired VTB offer must not trigger a native required-pack disconnect");
    }

    private static void pauseAndResume(Path root) throws Exception {
        for (var version : List.of(ProtocolVersion.MINECRAFT_1_20_3, ProtocolVersion.MINECRAFT_1_20_2)) {
            Path data = root.resolve("remove-pause-" + version.getProtocol());
            var h = new PackDeliveryTest.Harness(data);
            h.version = version;
            var first = rules(data, POLICY);
            var updated = rules(data, POLICY.replace("main.zip", "updated.zip"));
            try (var sender = h.sender()) {
                sender.apply(first);
                h.run(0);
                var old = h.sent.getLast();
                var cancelled = List.copyOf(h.jobs);
                h.onRemove = id -> {
                    reply(sender, h, old, "DISCARDED");
                    reply(sender, h, old, "DECLINED");
                };
                check(sender.remove(h.player) == (version == ProtocolVersion.MINECRAFT_1_20_3
                                ? PackSender.RemoveResult.REMOVED : PackSender.RemoveResult.LEGACY),
                        "report remote removal versus legacy limitation");
                check(h.removed.size() == (version == ProtocolVersion.MINECRAFT_1_20_3 ? 1 : 0),
                        "only supported clients receive a removal packet");
                check(h.removed.isEmpty() || h.removed.equals(List.of(old.getId())), "remove only tracked VTB pack IDs");
                String notice = PlainTextComponentSerializer.plainText().serialize(h.messages.getLast());
                check(notice.equals("[VTB] An administrator " + (version == ProtocolVersion.MINECRAFT_1_20_3
                                ? "removed your resource packs." : "paused resource pack delivery to you.")),
                        "player removal notice uses one prefix and omits pack names and task details");
                int notices = h.messages.size();
                sender.remove(h.player);
                check(h.messages.size() == notices, "repeated removal does not repeat the player notice");
                for (String status : List.of("DECLINED", "FAILED_DOWNLOAD", "FAILED_RELOAD", "SUCCESSFUL"))
                    reply(sender, h, old, status);
                cancelled.forEach(job -> job.run.run());
                for (int i = 0; i < 3; i++) sender.apply(updated);
                h.run(65000);
                check(h.sent.size() == 1 && h.kicked == null && h.status(sender).contains("paused delivery"),
                        "reload, stale receipts and timeout callbacks preserve pause without kicking");
                sender.apply(PackRules.disabled());
                sender.apply(updated);
                h.run(1000);
                check(h.sent.size() == 1, "disable and enable do not erase manual pause");
                check(sender.resend(h.player) == PackSender.ResendResult.SCHEDULED, "manual resend resumes paused delivery");
                h.run(1000);
                var current = h.sent.getLast();
                check(h.sent.size() == 2 && current.getUrl().endsWith("updated.zip")
                        && !current.getId().equals(old.getId()), "resend uses latest rules and a fresh offer ID");
                reply(sender, h, old, "FAILED_DOWNLOAD");
                reply(sender, h, current, "SUCCESSFUL");
                check(h.kicked == null, "old failure cannot affect resumed pack");

                h.onRemove = id -> {};
                sender.remove(h.player);
                sender.remove(h.player);
                sender.apply(updated);
                h.run(1000);
                check(h.sent.size() == 2, "repeated removal is idempotent and remains paused");
                h.server = "other";
                sender.connected(new ServerPostConnectEvent(h.player, null));
                h.run(1000);
                check(h.sent.size() == 3, "switching backend resumes normal assignment even for an unchanged pack");
                sender.status(new PlayerResourcePackStatusEvent(h.player, h.sent.getLast().getId(),
                        PlayerResourcePackStatusEvent.Status.DECLINED, h.sent.getLast()));
                check(h.kicked != null, "new active required offer still enforces required policy");
            }
        }
    }

    private static void pendingTasks(Path root) throws Exception {
        Path data = root.resolve("remove-pending");
        var h = new PackDeliveryTest.Harness(data);
        var policy = rules(data, POLICY.replace("delay: 0", "delay: 3"));
        try (var sender = h.sender()) {
            sender.apply(policy);
            var waiting = List.copyOf(h.jobs);
            check(sender.remove(h.player) == PackSender.RemoveResult.EMPTY, "unsent offer can be cancelled and paused");
            waiting.forEach(job -> job.run.run());
            sender.apply(policy);
            h.run(65000);
            check(h.sent.isEmpty() && h.kicked == null, "cancelled delayed callback cannot send after removal");
            h.server = "passive";
            sender.connected(new ServerPostConnectEvent(h.player, null));
            h.run(4000);
            check(h.sent.isEmpty(), "switch to passive assignment respects keep-existing");
            h.server = "lobby";
            sender.connected(new ServerPostConnectEvent(h.player, null));
            h.run(3000);
            check(h.sent.size() == 1, "later active assignment resumes after leaving pause");
        }
        var disabled = new PackDeliveryTest.Harness(root.resolve("remove-disabled"));
        try (var sender = disabled.sender()) {
            check(sender.remove(disabled.player) == PackSender.RemoveResult.EMPTY, "remove also pauses before delivery is enabled");
            sender.apply(policy);
            disabled.run(4000);
            check(disabled.sent.isEmpty(), "enabling delivery preserves preexisting manual pause");
        }
    }

    private static void batchRemoval(Path root) throws Exception {
        Path data = root.resolve("remove-batch");
        var h = new PackDeliveryTest.Harness(data);
        var players = new ArrayList<PackDeliveryTest.Harness>();
        players.add(h);
        for (int i = 1; i < 12; i++) players.add(new PackDeliveryTest.Harness(root.resolve("remove-batch-" + i)));
        h.online.clear();
        players.forEach(player -> h.online.add(player.player));
        players.get(4).version = ProtocolVersion.MINECRAFT_1_20_2;
        var policy = rules(data, POLICY);
        try (var sender = h.sender()) {
            sender.apply(policy);
            h.run(0);
            var stale = List.copyOf(h.jobs);
            check(sender.remove(players.get(8).player) == PackSender.RemoveResult.EMPTY, "remove cancels queued reload entry");
            var summary = sender.removeAll();
            check(summary.removed() == 4 && summary.legacy() == 1 && summary.empty() == 7 && summary.skipped() == 0,
                    "remove all separates removed, legacy and unsent players");
            sender.resendAll(h.player);
            var bulk = h.jobs.getLast();
            summary = sender.removeAll();
            check(summary.empty() == 12 && summary.removed() == 0 && summary.legacy() == 0,
                    "removal during bulk preparation cancels all unsent replacement requests");
            bulk.run.run();
            stale.forEach(job -> job.run.run());
            for (var player : players) for (var offer : List.copyOf(player.sent))
                reply(sender, player, offer, "FAILED_DOWNLOAD");
            sender.apply(policy);
            h.run(65000);
            check(players.stream().mapToInt(player -> player.sent.size()).sum() == 5
                    && players.stream().allMatch(player -> player.kicked == null),
                    "remove all cancels batch, delayed reload and stale timeouts without sending or kicking");
            sender.resendAll(h.player);
            for (int i = 0; i < 3; i++) h.run(i == 0 ? 0 : 1000);
            check(players.stream().mapToInt(player -> player.sent.size()).sum() == 17,
                    "new explicit resend all resumes removed players in batches");
        }
    }

    private static void bulkCommandSequences(Path root) throws Exception {
        for (boolean runFirstWave : List.of(false, true)) {
            Path data = root.resolve("remove-sequence-" + runFirstWave);
            var h = new PackDeliveryTest.Harness(data);
            var players = new ArrayList<PackDeliveryTest.Harness>();
            players.add(h);
            for (int i = 1; i < 30; i++) players.add(new PackDeliveryTest.Harness(data.resolve("player-" + i)));
            h.online.clear();
            players.forEach(player -> h.online.add(player.player));
            players.getLast().version = ProtocolVersion.MINECRAFT_1_20_2;
            var policy = rules(data, POLICY);
            var updated = rules(data, POLICY.replace("main.zip", "latest.zip"));
            try (var sender = h.sender()) {
                sender.apply(policy);
                for (int i = 0; i < 6; i++) h.run(i == 0 ? 0 : 1000);
                var old = players.stream().map(player -> player.sent.getLast()).toList();
                var oldTasks = List.copyOf(h.jobs);
                check(sender.resendAll(h.player), "bulk resend starts with thirty in-flight required packs");
                for (int i = 0; i < players.size(); i++) {
                    reply(sender, players.get(i), old.get(i), "DECLINED");
                    reply(sender, players.get(i), old.get(i), "FAILED_DOWNLOAD");
                }
                check(players.stream().allMatch(player -> player.kicked == null),
                        "bulk resend retires every old request before its first wave, including queued players");
                if (runFirstWave) h.run(1000);
                sender.apply(updated);
                for (int i = 0; i < 6; i++) {
                    int before = players.stream().mapToInt(player -> player.sent.size()).sum();
                    h.run(1000);
                    check(players.stream().mapToInt(player -> player.sent.size()).sum() - before <= 5,
                            "reload takeover does not merge already-withdrawn and newly-withdrawn waves");
                }
                check(players.stream().allMatch(player -> player.sent.getLast().getUrl().endsWith("latest.zip")),
                        "reload continues queued bulk intent using latest rules");
                for (var player : players) reply(sender, player, player.sent.getLast(), "SUCCESSFUL");

                sender.removeAll();
                sender.resendAll(h.player);
                if (runFirstWave) h.run(1000);
                sender.apply(updated);
                for (int i = 0; i < 6; i++) h.run(1000);
                check(players.stream().allMatch(player -> !player.status(sender).contains("paused delivery")
                                && player.sent.getLast().getUrl().endsWith("latest.zip")),
                        "remove all, resend all and reload resume everyone even before the first wave");

                sender.resendAll(h.player);
                if (runFirstWave) h.run(1000);
                var cancelled = List.copyOf(h.jobs);
                sender.removeAll();
                sender.apply(policy);
                int sent = players.stream().mapToInt(player -> player.sent.size()).sum();
                for (var task : cancelled) task.run.run();
                for (var task : oldTasks) task.run.run();
                for (var player : players) for (var offer : List.copyOf(player.sent))
                    for (String status : List.of("DECLINED", "FAILED_DOWNLOAD", "FAILED_RELOAD", "DISCARDED", "SUCCESSFUL"))
                        reply(sender, player, offer, status);
                h.run(120000);
                check(players.stream().mapToInt(player -> player.sent.size()).sum() == sent
                                && players.stream().allMatch(player -> player.kicked == null
                                && player.status(sender).contains("paused delivery")),
                        "latest remove wins over all earlier bulk tasks, timeouts, receipts and subsequent reload");
            }
        }
    }

    private static void replacementSpacing(Path root) throws Exception {
        for (int delay : List.of(0, 1)) {
            Path data = root.resolve("remove-spacing-" + delay);
            var h = new PackDeliveryTest.Harness(data);
            var players = new ArrayList<PackDeliveryTest.Harness>();
            players.add(h);
            for (int i = 1; i < 6; i++) players.add(new PackDeliveryTest.Harness(data.resolve("player-" + i)));
            h.online.clear();
            players.forEach(player -> h.online.add(player.player));
            try (var sender = h.sender()) {
                sender.apply(rules(data, POLICY.replace("delay: 0", "delay: " + delay)));
                h.run(delay * 1000L);
                h.run(1000);
                check(players.stream().mapToInt(player -> player.sent.size()).sum() == 6, "initial six-player delivery");
                sender.resendAll(h.player);
                h.run(0);
                h.run(999);
                check(players.stream().mapToInt(player -> player.sent.size()).sum() == 6,
                        "bulk replacement leaves a full one-second interval");
                h.run(1);
                check(players.stream().mapToInt(player -> player.sent.size()).sum() == 11,
                        "configured delay and replacement interval overlap, with only five starts in the first wave");
                h.run(1000);
                check(players.stream().mapToInt(player -> player.sent.size()).sum() == 12,
                        "second wave starts a second later without restarting the first wave");
            }
        }
    }

    private static void commands(Path root) throws Exception {
        Path data = root.resolve("remove-commands");
        var h = new PackDeliveryTest.Harness(data);
        var policy = rules(data, POLICY);
        try (var sender = h.sender()) {
            var dispatcher = new CommandDispatcher<CommandSource>();
            var command = new VelocityToolboxCommand(null, h.proxy, null, h.host, h.lang, sender).build().getNode();
            dispatcher.getRoot().addChild(command);
            h.permissions.addAll(Set.of("velocitytoolbox.command", "velocitytoolbox.command.pack"));
            check(!command.getChild("pack").getChild("remove").canUse(h.player), "remove needs its action permission");
            h.permissions.add("velocitytoolbox.command.pack.remove");
            for (String suffix : List.of("all", "ALL", "aLl")) {
                try {
                    dispatcher.execute("vtoolbox pack remove " + suffix, h.player);
                    throw new AssertionError("remove all bypassed dedicated permission: " + suffix);
                } catch (CommandSyntaxException expected) { }
            }
            check(dispatcher.getCompletionSuggestions(dispatcher.parse("vtoolbox pack remove ", h.player)).get()
                    .getList().stream().noneMatch(suggestion -> suggestion.getText().equals("all")), "all completion is permission filtered");
            sender.apply(policy);
            h.run(0);
            check(dispatcher.execute("vtoolbox pack remove Missing", h.player) == 0 && h.removed.isEmpty(),
                    "unknown player cannot remove another player's packs");
            check(dispatcher.execute("vtoolbox pack remove Tester", h.player) == 1 && h.removed.size() == 1,
                    "single remove command withdraws tracked pack");
            sender.apply(policy);
            h.run(1000);
            check(h.sent.size() == 1 && h.status(sender).contains("paused delivery"), "command pause is visible and survives reload");
            h.permissions.addAll(Set.of("velocitytoolbox.command.pack.remove.all", "velocitytoolbox.command.pack.resend"));
            check(command.getChild("pack").getChild("remove").getChild("all").canUse(h.player), "bulk remove permission accepted");
            check(dispatcher.getCompletionSuggestions(dispatcher.parse("vtoolbox pack remove ", h.player)).get()
                    .getList().stream().anyMatch(suggestion -> suggestion.getText().equals("all")), "authorized all completion visible");
            dispatcher.execute("vtoolbox pack", h.player);
            check(h.messages.stream().map(PlainTextComponentSerializer.plainText()::serialize)
                    .anyMatch(message -> message.contains("/vtb pack remove all")), "remove all appears in permission-filtered help");
            check(dispatcher.execute("vtoolbox pack resend Tester", h.player) == 1, "resend command resumes delivery");
            h.run(1000);
            check(h.sent.size() == 2, "resend restores removed pack");
            check(dispatcher.execute("vtoolbox pack remove ALL", h.player) == 1 && h.removed.size() == 2,
                    "case-insensitive bulk removal executes");
            check(h.messages.stream().map(PlainTextComponentSerializer.plainText()::serialize)
                    .anyMatch(message -> message.contains("Removal finished:")), "bulk removal reports real result counts");
        }
    }

    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
}
