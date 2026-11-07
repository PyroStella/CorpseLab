package com.pyrostella.corpselab.internal.network;

import com.pyrostella.corpselab.internal.config.ClientSettings;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** 客户端告知服务端自己的设置当前是什么值 */
public final class ClientSettingsPacket {

    private final Map<String, String> values;

    public ClientSettingsPacket(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public ClientSettingsPacket(FriendlyByteBuf buf) {
        int count = com.pyrostella.corpselab.internal.network.PacketReads.count(buf, 2);
        values = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            values.put(buf.readUtf(), buf.readUtf());
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(values.size());
        values.forEach((path, value) -> {
            buf.writeUtf(path);
            buf.writeUtf(value);
        });
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                ClientSettings.report(player, values);
            }
        });
        ctx.setPacketHandled(true);
    }
}
