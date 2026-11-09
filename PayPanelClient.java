package com.paypanel;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-only. When a payment message arrives, it prints a line right under it
 * with clickable buttons. A command is sent ONLY when you click a button.
 * Nothing is ever sent automatically.
 */
public class PayPanelClient implements ClientModInitializer {
    private static Pattern pattern;
    private static String pendingKey = null;
    private static long pendingTime = 0;

    @Override
    public void onInitializeClient() {
        PayPanelConfig.load();
        try {
            pattern = Pattern.compile(PayPanelConfig.get().messagePattern);
        } catch (Exception e) {
            System.err.println("[PayPanel] Bad messagePattern in config: " + e);
            return;
        }

        // Server/system messages (default).
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) handle(message.getString());
        });
        // Player chat only if enabled in the config.
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> {
            if (PayPanelConfig.get().listenToPlayerChat) handle(message.getString());
        });

        // Internal client-side commands that the chat buttons run.
        // They never leave your computer except for the real command they send.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("paypanel")
                .then(ClientCommandManager.literal("pay")
                    .then(ClientCommandManager.argument("player", StringArgumentType.word())
                        .then(ClientCommandManager.argument("amount", StringArgumentType.word())
                            .then(ClientCommandManager.argument("multiplier", DoubleArgumentType.doubleArg(0.01))
                                .executes(ctx -> {
                                    pay(StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "amount"),
                                        DoubleArgumentType.getDouble(ctx, "multiplier"));
                                    return 1;
                                })))))
                .then(ClientCommandManager.literal("stats")
                    .then(ClientCommandManager.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            lookup(PayPanelConfig.get().statsCommand,
                                   StringArgumentType.getString(ctx, "player"));
                            return 1;
                        })))
                .then(ClientCommandManager.literal("bal")
                    .then(ClientCommandManager.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            lookup(PayPanelConfig.get().balanceCommand,
                                   StringArgumentType.getString(ctx, "player"));
                            return 1;
                        })))));
    }

    private static void handle(String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return;
        try {
            String player = m.group("player");
            if (!player.matches("\\w{1,16}")) return; // valid usernames only
            BigDecimal amount = new BigDecimal(m.group("amount").replace(",", ""));
            if (amount.signum() <= 0) return;

            double mult = PayPanelConfig.get().bigMultiplier;
            String amt = fmt(amount);
            BigDecimal big = amount.multiply(BigDecimal.valueOf(mult));

            boolean xmas = PayPanelConfig.get().christmasTheme;
            MutableComponent line = Component.literal(xmas ? " " : "  ");
            if (xmas) line.append(Component.literal("❄ ").withStyle(ChatFormatting.WHITE));
            line.append(button("[" + trim(mult) + "x]",
                "/paypanel pay " + player + " " + amt + " " + mult,
                "Pay " + player + " " + fmt(big), ChatFormatting.GREEN));
            line.append(Component.literal(" "));
            line.append(button("[1x]",
                "/paypanel pay " + player + " " + amt + " 1.0",
                "Pay " + player + " " + amt, xmas ? ChatFormatting.RED : ChatFormatting.YELLOW));
            line.append(Component.literal(" "));
            line.append(button("[Stats]",
                "/paypanel stats " + player,
                "Check " + player + "'s stats", xmas ? ChatFormatting.WHITE : ChatFormatting.AQUA));
            line.append(Component.literal(" "));
            line.append(button("[Balance]",
                "/paypanel bal " + player,
                "Check " + player + "'s balance", ChatFormatting.GOLD));
            if (xmas) line.append(Component.literal(" ★").withStyle(ChatFormatting.GOLD));

            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.displayClientMessage(line, false);
        } catch (Exception ignored) {
            // pattern missing a group or number not parsable: do nothing
        }
    }

    private static MutableComponent button(String label, String command, String hover, ChatFormatting color) {
        return Component.literal(label).withStyle(style -> style
            .withColor(color)
            .withBold(true)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    /** Pay back baseAmount * multiplier. Runs only from a button click. */
    private static void pay(String player, String baseAmount, double multiplier) {
        if (!player.matches("\\w{1,16}")) return;
        BigDecimal base;
        try {
            base = new BigDecimal(baseAmount);
        } catch (NumberFormatException e) {
            return;
        }
        if (base.signum() <= 0) return;

        BigDecimal payout = base.multiply(BigDecimal.valueOf(multiplier));
        double cap = PayPanelConfig.get().maxPayout;
        if (cap > 0 && payout.compareTo(BigDecimal.valueOf(cap)) > 0) {
            info("Blocked: " + fmt(payout) + " is above your maxPayout cap (" + cap + ").");
            return;
        }
        PayPanelConfig cfg = PayPanelConfig.get();
        if (cfg.doubleClickToPay) {
            // First click only arms the payment. A second click on the same
            // button within the time window actually sends it.
            String key = player + "|" + fmt(payout);
            long now = System.currentTimeMillis();
            if (!key.equals(pendingKey) || now - pendingTime > cfg.doubleClickWindowMs) {
                pendingKey = key;
                pendingTime = now;
                info("Click again to confirm: pay " + player + " " + fmt(payout) + ".");
                return;
            }
            pendingKey = null;
        }
        send(cfg.payCommand, player, payout);
    }

    private static void lookup(String template, String player) {
        if (!player.matches("\\w{1,16}")) return;
        send(template, player, null);
    }

    private static void send(String template, String player, BigDecimal amount) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String cmd = template.replace("{player}", player);
        if (amount != null) cmd = cmd.replace("{amount}", fmt(amount));
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        mc.player.connection.sendCommand(cmd);
    }

    private static String fmt(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static void info(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[PayPanel] " + msg), false);
        }
    }
}
