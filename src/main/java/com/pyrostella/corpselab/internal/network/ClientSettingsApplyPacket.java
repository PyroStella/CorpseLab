package com.pyrostella.corpselab.internal.network;

import com.pyrostella.corpselab.client.sync.ClientSettingsHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 让客户端修改它自己的一项设置，因为玩家在菜单里点了它
 * 只会指向客户端设置，接收端还会自行校验
 */
public final class ClientSettingsApplyPacket {

    private final String path;
    private final String value;

    public ClientSettingsApplyPacket(String path, String value) {
        this.path = path;
        this.value = value;
    }

    public ClientSettingsApplyPacket(FriendlyByteBuf buf) {
        path = buf.readUtf();
        value = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(path);
        buf.writeUtf(value);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientSettingsHandler.apply(path, value));
    }
}
