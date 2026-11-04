package com.pyrostella.corpselab.client;

import com.pyrostella.corpselab.config.ConfigRegistry;
import com.pyrostella.corpselab.net.ClientSettingsPacket;
import com.pyrostella.corpselab.net.CorpseLabNetwork;

import java.util.HashMap;
import java.util.Map;

/** The client half of the settings menu: reports its own settings, and changes them. */
public final class ClientSettingsHandler {

    private ClientSettingsHandler() {
    }

    /** Sends every client-side setting and its current value to the server. */
    public static void report() {
        Map<String, String> values = new HashMap<>();
        for (ConfigRegistry.Entry entry : ConfigRegistry.entries()) {
            if (!entry.serverSide() && ConfigRegistry.isLoaded(entry)) {
                values.put(entry.path(), ConfigRegistry.encode(ConfigRegistry.get(entry)));
            }
        }
        CorpseLabNetwork.CHANNEL.sendToServer(new ClientSettingsPacket(values));
    }

    public static void apply(String path, String value) {
        ConfigRegistry.Entry entry = ConfigRegistry.find(path);
        // A server has no business setting anything but this player's own display
        // settings, so anything else is dropped rather than trusted.
        if (entry == null || entry.serverSide()) {
            return;
        }

        Object decoded = ConfigRegistry.decode(entry, value);
        if (decoded != null) {
            ConfigRegistry.set(entry, decoded);
        }
        report();
    }
}
