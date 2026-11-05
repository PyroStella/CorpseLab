package com.pyrostella.corpselab.net;

import com.pyrostella.corpselab.client.ClientSettingsHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 请客户端上报自己的设置，菜单才能把它们显示出来 */
public final class ClientSettingsRequestPacket {

    public ClientSettingsRequestPacket() {
    }

    public ClientSettingsRequestPacket(FriendlyByteBuf buf) {
    }

    public void encode(FriendlyByteBuf buf) {
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> ClientSettingsHandler::report));
        context.get().setPacketHandled(true);
    }
}
