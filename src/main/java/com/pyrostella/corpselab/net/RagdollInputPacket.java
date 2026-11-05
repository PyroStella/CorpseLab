package com.pyrostella.corpselab.net;

import com.pyrostella.corpselab.entity.RagdollEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 倒地玩家传来的移动输入，用于翻滚
 *
 * <p>原版只会为指定了控制乘客的载具转发乘客输入，而一旦声明控制乘客
 * 客户端也就获得了用 ServerboundMoveVehiclePacket 推动载具的能力
 * 本模组对身体的位置是服务端权威，所以自己带着输入走
 * 把客户端挡在物理之外
 *
 * <p>只在真正有输入的那些 tick 才发送，空闲躺着的 ragdoll 不产生开销
 */
public final class RagdollInputPacket {

    private final float strafe;
    private final float forward;

    public RagdollInputPacket(float strafe, float forward) {
        this.strafe = strafe;
        this.forward = forward;
    }

    public RagdollInputPacket(FriendlyByteBuf buf) {
        this.strafe = buf.readFloat();
        this.forward = buf.readFloat();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeFloat(strafe);
        buf.writeFloat(forward);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null && player.getVehicle() instanceof RagdollEntity ragdoll) {
                // 夹紧范围，因为这个值是从客户端来的
                ragdoll.setRollInput(
                        Math.max(-1.0F, Math.min(1.0F, strafe)),
                        Math.max(-1.0F, Math.min(1.0F, forward)));
            }
        });
        ctx.setPacketHandled(true);
    }
}
