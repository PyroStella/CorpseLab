package com.pyrostella.corpselab.internal.config;

import com.pyrostella.corpselab.internal.network.ClientSettingsApplyPacket;
import com.pyrostella.corpselab.internal.network.ClientSettingsRequestPacket;
import com.pyrostella.corpselab.internal.network.CorpseLabNetwork;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.internal.menu.ConfigMenu;
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
 * 缓存每个玩家客户端自报的设置值。
 *
 * <p>客户端设置存在玩家本机，服务端读不到，只能向客户端询问后缓存；
 * 没回应的玩家对应项标为未知。
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

    /** 记录客户端自报的设置；菜单开着则刷新 */
    public static void report(ServerPlayer player, Map<String, String> values) {
        REPORTED.put(player.getUUID(), Map.copyOf(values));
        if (player.containerMenu instanceof ConfigMenu menu) {
            menu.refresh();
        }
    }

    /** 通知客户端修改某项设置，并先按新值缓存 */
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