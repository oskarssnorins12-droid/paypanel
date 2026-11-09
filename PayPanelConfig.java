package com.paypanel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Settings file: .minecraft/config/paypanel.json (edit it to switch servers). */
public class PayPanelConfig {
    // Must contain the groups (?<player>...) and (?<amount>...).
    public String messagePattern =
        "^(?<player>\\w{1,16}) (?:paid|sent) you \\$?(?<amount>[\\d,]+(?:\\.\\d+)?)";

    // {player} and {amount} get filled in. A leading / is optional.
    public String payCommand = "/pay {player} {amount}";
    public String balanceCommand = "/bal {player}";
    public String statsCommand = "/stats {player}";

    // What the "big" button multiplies the received amount by.
    public double bigMultiplier = 2.0;

    // true = Christmas look: red/green/white/gold buttons with snowflake and star.
    // If a symbol shows as a box in your game, set this to false.
    public boolean christmasTheme = true;

    // true = a pay button needs two clicks (first arms, second sends).
    public boolean doubleClickToPay = true;
    // How long (milliseconds) you have to make the second click.
    public int doubleClickWindowMs = 3000;

    // Safety cap: the mod refuses to send a payment above this. 0 = no cap.
    public double maxPayout = 0;

    // false = only react to server/system messages, so players can't fake
    // a payment line by typing it in chat. Turn on only if your server needs it.
    public boolean listenToPlayerChat = false;

    private static PayPanelConfig instance = new PayPanelConfig();
    private static final Gson GSON =
        new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static PayPanelConfig get() {
        return instance;
    }

    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("paypanel.json");
        try {
            if (Files.exists(file)) {
                PayPanelConfig loaded = GSON.fromJson(Files.readString(file), PayPanelConfig.class);
                if (loaded != null) instance = loaded;
            } else {
                Files.writeString(file, GSON.toJson(instance));
            }
        } catch (Exception e) {
            System.err.println("[PayPanel] Could not read config, using defaults: " + e);
        }
    }
}
