package com.pyrostella.corpselab.net;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.menu.ConfigMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端所知的、每个玩家客户端自己的设置值
 *
 * <p>客户端设置属于表现层（ragdoll 相机、操作提示），保存在该玩家本机的文件里
 * 所以服务端无法直接读写，而设置菜单又要显示它们
 * 这就只能向客户端询问，再把答复缓存起来
 * 从不回应的玩家看到的那几行就标成未知
 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID)
public final class ClientSettings {

    private static final Map<UUID, Map<String, String>> REPORTED = new ConcurrentHashMap<>();

    private ClientSettings() {
    }

    public static void request(ServerPlayer player) {
        CorpseLabNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ClientSettingsRequestPacket());
    }

    public static String get(ServerPlayer player, String path) {
        return REPORTED.getOrDefault(player.getUUID(), Map.of()).get(path);
    }

    /** 记下客户端自报的设置值，若菜单正开着就重画一遍 */
    public static void report(ServerPlayer player, Map<String, String> values) {
        REPORTED.put(player.getUUID(), Map.copyOf(values));
        if (player.containerMenu instanceof ConfigMenu menu) {
            menu.refresh();
        }
    }

    /** 让客户端改它自己的一项设置，并假定它会照做 */
    public static void set(ServerPlayer player, String path, String value) {
        Map<String, String> known = new HashMap<>(REPORTED.getOrDefault(player.getUUID(), Map.of()));
        known.put(path, value);
        REPORTED.put(player.getUUID(), Map.copyOf(known));

        CorpseLabNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ClientSettingsApplyPacket(path, value));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        REPORTED.remove(event.getEntity().getUUID());
    }
}
