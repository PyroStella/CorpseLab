package com.pyrostella.corpselab.net;

import com.pyrostella.corpselab.entity.RagdollEntity;
import com.pyrostella.corpselab.ragdoll.RagdollController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 玩家请求倒下或请求起身，两者都由服务端决定 */
public final class RagdollActionPacket {

    public enum Action {
        TUMBLE,
        GET_UP
    }

    private final Action action;

    public RagdollActionPacket(Action action) {
        this.action = action;
    }

    public RagdollActionPacket(FriendlyByteBuf buf) {
        this.action = buf.readEnum(Action.class);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            switch (action) {
                case TUMBLE -> RagdollController.tryManualCorpseLab(player);
                case GET_UP -> {
                    if (player.getVehicle() instanceof RagdollEntity ragdoll && ragdoll.canGetUp()) {
                        ragdoll.getUp();
                    }
                }
            }
        });
        ctx.setPacketHandled(true);
    }
}
