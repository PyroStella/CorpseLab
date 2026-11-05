package com.pyrostella.corpselab.net;

import com.pyrostella.corpselab.config.CorpseLabConfig;
import com.pyrostella.corpselab.entity.SettlingBody;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 玩家伸手去抓一具身体，或者松手放开
 *
 * <p>包里只说抓取键有没有按下。抓哪具身体、抓哪个肢体
 * 全由服务端依据玩家自己的位置和视线方向决定，所以客户端无法
 * 声称自己隔着半张地图或视线之外抓着东西
 */
public final class RagdollGrabPacket {

    private final boolean grabbing;

    public RagdollGrabPacket(boolean grabbing) {
        this.grabbing = grabbing;
    }

    public RagdollGrabPacket(FriendlyByteBuf buf) {
        this.grabbing = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(grabbing);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || !CorpseLabConfig.SERVER.grabEnabled.get()) {
                return;
            }

            double reach = CorpseLabConfig.SERVER.grabReach.get();
            AABB search = player.getBoundingBox().inflate(reach + 2.0D);

            for (SettlingBody body : player.level().getEntitiesOfClass(SettlingBody.class, search)) {
                if (!grabbing) {
                    // 放开这名玩家原先抓着的东西，不管它在哪里
                    if (body.isGrabbedBy(player)) {
                        body.releaseGrab();
                    }
                    continue;
                }

                if (body.tryGrab(player)) {
                    // 已经静止的身体必须重新开始模拟，才拖得动
                    body.wake();
                    return;
                }
            }
        });
        ctx.setPacketHandled(true);
    }
}
