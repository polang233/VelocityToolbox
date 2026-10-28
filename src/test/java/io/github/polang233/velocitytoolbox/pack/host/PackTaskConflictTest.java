package io.github.polang233.velocitytoolbox.pack.host;

import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import io.github.polang233.velocitytoolbox.config.ResourceFiles;
import io.github.polang233.velocitytoolbox.pack.config.PackConfig;
import io.github.polang233.velocitytoolbox.pack.config.PackRules;
import io.github.polang233.velocitytoolbox.pack.delivery.PackSender;
import net.kyori.adventure.text.Component;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 故意调用已取消任务、注入同步回执，以固定顺序重现切服与重载的交错。 */
final class PackTaskConflictTest {
    private static final String POLICY = """
            enabled: true
            settings:
              delay: 0
              timeout: 60
              required: true
            packs:
              a:
                - url: https://example.com/a.zip
                  hash: 0123456789abcdef0123456789abcdef01234567
              b:
                - url: https://example.com/b.zip
                  hash: 0123456789abcdef0123456789abcdef01234567
              c:
                - url: https://example.com/c.zip
                  hash: 0123456789abcdef0123456789abcdef01234567
            servers:
              default:
                packs: [a]
              beta:
                packs: [b]
              gamma:
                packs: [c]
            """;

    static void run(Path root) throws Exception {
        rapidSwitches(root);
        preConnectReceipt(root);
        removalReceipts(root);
        queuedBulkSwitch(root);
        changedSelectionDuringWait(root);
        synchronousRetry(root);
    }

    private static PackRules rules(Path root, String yaml) throws Exception {
        Path file = root.resolve("policy.yml");
        Files.writeString(file, yaml);
        return PackRules.read(ResourceFiles.loadYaml(file), List.of());
    }

    private static void reply(PackSender sender, PackDeliveryTest.Harness player, ResourcePackInfo offer, String status) {
        var event = new PlayerResourcePackStatusEvent(player.player, offer.getId(),
                PlayerResourcePackStatusEvent.Status.valueOf(status), offer);
        sender.status(event);
        // 模拟代理/客户端在处理完事件后仍执行原生必需标记，不能只验证 VTB 没有踢人。
        if (status.equals("DECLINED") && offer.getShouldForce() && !event.isOverwriteKick())
            player.player.disconnect(Component.text("Native required pack declined"));
    }

    private static void rapidSwitches(Path root) throws Exception {
        for (var version : List.of(ProtocolVersion.MINECRAFT_1_20_3, ProtocolVersion.MINECRAFT_1_17)) {
            var h = new PackDeliveryTest.Harness(root.resolve("conflict-switch-" + version.getProtocol()));
            h.version = version;
            var rules = rules(root, POLICY);
            try (var sender = h.sender()) {
                sender.apply(rules);
                h.run(0);
                var a = h.sent.getLast();
                var timeout = h.jobs.getLast();
                h.server = "beta";
                sender.connected(new ServerPostConnectEvent(h.player, null));
                var beta = h.jobs.getLast();
                h.run(400);
                h.server = "gamma";
                sender.connected(new ServerPostConnectEvent(h.player, null));
                for (int i = 0; i < 4; i++) {
                    sender.apply(rules);
                    sender.resend(h.player);
                }
                beta.run.run();
                timeout.run.run();
                reply(sender, h, a, "DECLINED");
                reply(sender, h, a, "FAILED_DOWNLOAD");
                check(h.kicked == null, "retired required offer cannot disconnect during a switch");
                h.run(599);
                check(h.sent.size() == 1, "withdrawn pack has a full one-second settling interval");
                h.run(1);
                check(h.sent.size() == 2 && h.sent.getLast().getUrl().endsWith("c.zip"),
                        "only latest server is offered after repeated reload and cancelled callbacks");
                var c = h.sent.getLast();
                int removed = h.removed.size();
                reply(sender, h, a, "FAILED_RELOAD");
                timeout.run.run();
                check(h.kicked == null && h.removed.size() == removed, "old receipt and timeout cannot cancel new pack");
                reply(sender, h, c, "DECLINED");
                check(h.kicked != null, "active required pack still enforced");
            }
        }
    }

    private static void preConnectReceipt(Path root) throws Exception {
        var h = new PackDeliveryTest.Harness(root.resolve("conflict-before-connect"));
        try (var sender = h.sender()) {
            sender.apply(rules(root, POLICY));
            h.run(0);
            var a = h.sent.getLast();
            var timeout = h.jobs.getLast();
            h.server = "beta";
            // 实际子服已改变，但 PostConnect 事件尚未取得 sender 锁。
            reply(sender, h, a, "FAILED_DOWNLOAD");
            timeout.run.run();
            check(h.kicked == null, "receipt arriving before PostConnect cannot enforce old assignment");
            sender.connected(new ServerPostConnectEvent(h.player, null));
            h.run(1000);
            check(h.sent.size() == 2 && h.sent.getLast().getUrl().endsWith("b.zip"), "new assignment survives receipt race");
        }
    }

    private static void removalReceipts(Path root) throws Exception {
        var h = new PackDeliveryTest.Harness(root.resolve("conflict-remove"));
        try (var sender = h.sender()) {
            sender.apply(rules(root, POLICY.replace("packs: [a]", "packs: [a, b]")));
            h.run(0);
            List<ResourcePackInfo> old = List.copyOf(h.sent);
            h.onRemove = id -> {
                // 一次撤掉多个包时，第一个撤包动作可以带来其它旧包的失败回执。
                for (var offer : old) reply(sender, h, offer, "DECLINED");
            };
            sender.apply(PackRules.disabled());
            check(h.kicked == null, "all retired offers detach before any removal callback");
        }
    }

    private static void queuedBulkSwitch(Path root) throws Exception {
        var h = new PackDeliveryTest.Harness(root.resolve("conflict-bulk"));
        var players = new ArrayList<PackDeliveryTest.Harness>();
        players.add(h);
        for (int i = 1; i < 12; i++) players.add(new PackDeliveryTest.Harness(root.resolve("conflict-bulk-" + i)));
        h.online.clear();
        players.forEach(player -> h.online.add(player.player));
        var rules = rules(root, POLICY);
        try (var sender = h.sender()) {
            sender.apply(rules);
            for (int wave = 0; wave < 3; wave++) {
                h.run(wave == 0 ? 0 : 1000);
                for (var player : players) if (!player.sent.isEmpty())
                    reply(sender, player, player.sent.getLast(), "SUCCESSFUL");
            }
            sender.resendAll(h.player);
            h.run(0);
            h.run(400);
            var switched = players.get(6);
            var old = switched.sent.getLast();
            switched.server = "beta";
            sender.connected(new ServerPostConnectEvent(switched.player, null));
            var resent = players.get(8);
            for (int i = 0; i < 4; i++) sender.resend(resent.player);
            h.run(600);
            h.run(400);
            var b = switched.sent.getLast();
            check(switched.sent.size() == 2 && b.getUrl().endsWith("b.zip"), "queued bulk entry cannot restart switched player's offer");
            check(resent.sent.size() == 2 && resent.removed.size() == 1,
                    "repeated single resends coalesce and supersede the older bulk entry");
            int removed = switched.removed.size();
            reply(sender, switched, old, "DECLINED");
            reply(sender, switched, old, "FAILED_DOWNLOAD");
            reply(sender, switched, b, "SUCCESSFUL");
            for (int i = 0; i < 4; i++) sender.apply(rules);
            h.run(10000);
            check(switched.kicked == null && switched.sent.size() == 2 && switched.removed.size() == removed,
                    "bulk, switch and repeated reload preserve current successful pack");
        }
    }

    private static void changedSelectionDuringWait(Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("conflict-permission"));
        var h = new PackDeliveryTest.Harness(data);
        Path file = data.resolve("policy.yml");
        Files.writeString(file, POLICY.replace("delay: 0", "delay: 1"));
        var node = ResourceFiles.loadYaml(file);
        node.node("packs", "a", 0, "conditions", "permission").set("pack.vip");
        var fallback = node.node("packs", "a").appendListNode();
        fallback.node("url").set("https://example.com/c.zip");
        fallback.node("hash").set("0123456789abcdef0123456789abcdef01234567");
        node.node("servers", "beta", "packs").set(List.of("a", "b"));
        h.permissions.add("pack.vip");
        try (var sender = h.sender()) {
            sender.apply(PackRules.read(node, List.of()));
            h.run(1000);
            var a = h.sent.getLast();
            reply(sender, h, a, "SUCCESSFUL");
            h.server = "beta";
            sender.connected(new ServerPostConnectEvent(h.player, null));
            h.permissions.clear();
            h.run(1000);
            check(h.sent.size() == 1 && h.removed.contains(a.getId()),
                    "selection changed at dispatch still withdraws old pack before settling");
            reply(sender, h, a, "FAILED_DOWNLOAD");
            h.run(999);
            check(h.sent.size() == 1 && h.kicked == null, "late permission selection respects full settling interval");
            h.run(1);
            check(h.sent.size() == 3 && h.sent.get(1).getUrl().endsWith("c.zip")
                    && h.sent.get(2).getUrl().endsWith("b.zip"), "latest permission variants dispatch in order after settling");
        }
    }

    private static void synchronousRetry(Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("conflict-retry"));
        Path packs = Files.createDirectories(data.resolve("packs"));
        for (String name : List.of("a", "b", "c")) {
            try (var zip = new ZipOutputStream(Files.newOutputStream(packs.resolve(name + ".zip")))) {
                zip.putNextEntry(new ZipEntry("pack.mcmeta"));
                zip.write("{\"pack\":{\"pack_format\":22,\"description\":\"fixture\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        var h = new PackDeliveryTest.Harness(data);
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        Path config = data.resolve("host.yml");
        Files.writeString(config, "enabled: true\nbind: 127.0.0.1\nport: " + port
                + "\npublic-url: http://127.0.0.1:" + port + "\npacks-directory: packs\n");
        try (var host = h.host; var sender = h.sender()) {
            host.apply(host.prepare(PackConfig.from(ResourceFiles.loadYaml(config))));
            // 从解析树去掉外链样例哈希，使用扫描出来的本地哈希。
            Path policy = data.resolve("local.yml");
            Files.writeString(policy, POLICY.replace("packs: [a]", "packs: [a, b]"));
            var node = ResourceFiles.loadYaml(policy);
            for (String name : List.of("a", "b", "c")) {
                node.node("packs", name, 0, "hash").raw(null);
                node.node("packs", name, 0, "url").set("@" + name + ".zip");
            }
            var rules = PackRules.read(node, host.packs(), packs, true);
            h.onSend = offer -> {
                if (h.sent.size() == 1) reply(sender, h, offer, "FAILED_DOWNLOAD");
            };
            sender.apply(rules);
            h.run(0);
            check(h.sent.size() == 1, "synchronous download failure stops remaining suffix dispatch");
            var failed = h.sent.getFirst();
            var retry = h.jobs.getLast();
            for (int i = 0; i < 4; i++) sender.apply(rules);
            h.run(4999);
            check(h.sent.size() == 1, "repeated reload does not advance or duplicate pending retry");
            h.run(1);
            check(h.sent.size() == 3, "retry sends a consistent two-pack selection");
            h.server = "gamma";
            sender.connected(new ServerPostConnectEvent(h.player, null));
            h.run(1000);
            var c = h.sent.getLast();
            int count = h.sent.size(), removed = h.removed.size();
            reply(sender, h, c, "SUCCESSFUL");
            retry.run.run();
            reply(sender, h, failed, "FAILED_DOWNLOAD");
            check(h.kicked == null && h.sent.size() == count && h.removed.size() == removed,
                    "late retry callback and failure cannot replace latest loaded pack");

            reply(sender, h, c, "FAILED_DOWNLOAD");
            sender.resend(h.player);
            h.run(1000);
            var pending = h.sent.getLast();
            reply(sender, h, pending, "FAILED_DOWNLOAD");
            var pendingRetry = h.jobs.getLast();
            int beforeRemove = h.sent.size();
            check(sender.remove(h.player) == PackSender.RemoveResult.EMPTY,
                    "withdrawal during retry wait cancels the pending retry without a sent pack");
            sender.apply(rules);
            pendingRetry.run.run();
            reply(sender, h, pending, "FAILED_DOWNLOAD");
            h.run(20000);
            check(h.sent.size() == beforeRemove && h.kicked == null && h.status(sender).contains("paused delivery"),
                    "removed player's retry cannot restart on callback, repeated failure or reload");
        }
    }

    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
}
