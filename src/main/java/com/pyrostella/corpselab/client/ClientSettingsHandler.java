package com.pyrostella.corpselab.client;

import com.pyrostella.corpselab.config.ConfigRegistry;
import com.pyrostella.corpselab.net.ClientSettingsPacket;
import com.pyrostella.corpselab.net.CorpseLabNetwork;

import java.util.HashMap;
import java.util.Map;

/** 设置菜单的客户端部分：上报自己的设置，并修改它们 */
public final class ClientSettingsHandler {

    private ClientSettingsHandler() {
    }

    /** 把每一项客户端设置及其当前值发送给服务端 */
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
        // 服务端除了这名玩家自己的显示设置，不该改动任何东西
        // 所以其余的一律丢弃，而不采信
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
