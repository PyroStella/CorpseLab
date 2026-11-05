package com.pyrostella.corpselab.net;

import com.pyrostella.corpselab.ragdoll.CorpseLabTriggers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端上报自己的运动状态，让撞击触发器能拿到真实速度
 *
 * <p>服务端并不知道玩家移动得有多快，因为玩家对自己的移动拥有客户端权威
 * 服务端只能从位置包反推位移增量，所以要检测
 * “刚才还在每秒 30 格，现在不是了”这种情况
 * 判断只能从客户端做起
 *
 * <p>只在确实高速移动时才发送，另外补一段短尾好让停下那一刻也被报上来
 * 因此正常游玩时它不产生任何开销
 */
public final class PlayerMotionPacket {

    private final double x;
    private final double y;
    private final double z;

    public PlayerMotionPacket(Vec3 motion) {
        this.x = motion.x;
        this.y = motion.y;
        this.z = motion.z;
    }

    public PlayerMotionPacket(FriendlyByteBuf buf) {
        this.x = buf.readFloat();
        this.y = buf.readFloat();
        this.z = buf.readFloat();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeFloat((float) x);
        buf.writeFloat((float) y);
        buf.writeFloat((float) z);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                CorpseLabTriggers.onMotionSample(player, new Vec3(x, y, z));
            }
        });
        ctx.setPacketHandled(true);
    }
}
