package com.pyrostella.corpselab.internal.network;

import com.pyrostella.corpselab.internal.corpse.RagdollEntity;
import com.pyrostella.corpselab.physics.RigidBody;
import com.pyrostella.corpselab.client.sync.ClientPoseHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.function.Supplier;

/**
 * 一个 tick 的 ragdoll 姿势，发给所有正在跟踪该 entity 的客户端
 *
 * <p>包里自带锚点，而不是依赖 entity 的同步位置
 * 这才是止住残影的关键：原版发送 entity 位移用的是量化增量
 * 到达时晚一个 tick，于是拿 entity 的客户端位置去量肢体偏移
 * 就把肢体和锚点放到了两套时钟上，高速移动的 ragdoll 会肉眼可见地
 * 撕裂成第二具闪烁的身体
 *
 * <p>锚点在包里，一条消息就足以精确知道某个肢体的世界坐标
 * 渲染时只要减去 entity 实际被画在的位置
 */
public final class RagdollPosePacket {

    private final int entityId;
    private final double anchorX;
    private final double anchorY;
    private final double anchorZ;
    private final Vector3f[] positions;
    private final Quaternionf[] rotations;

    private RagdollPosePacket(int entityId, double anchorX, double anchorY, double anchorZ,
                              Vector3f[] positions, Quaternionf[] rotations) {
        this.entityId = entityId;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.positions = positions;
        this.rotations = rotations;
    }

    public static RagdollPosePacket of(RagdollEntity ragdoll) {
        Vector3f[] positions = new Vector3f[RagdollEntity.PART_COUNT];
        Quaternionf[] rotations = new Quaternionf[RagdollEntity.PART_COUNT];

        // 锚点就是发送那一刻 entity 的权威位置，偏移量都以它为基准量出
        // 所以两者永远不会对不上
        double ax = ragdoll.getX();
        double ay = ragdoll.getY();
        double az = ragdoll.getZ();

        for (int i = 0; i < RagdollEntity.PART_COUNT; i++) {
            RigidBody body = ragdoll.body(i);
            positions[i] = new Vector3f(
                    (float) (body.pos.x - ax),
                    (float) (body.pos.y - ay),
                    (float) (body.pos.z - az));
            rotations[i] = new Quaternionf(
                    (float) body.rot.x, (float) body.rot.y,
                    (float) body.rot.z, (float) body.rot.w);
        }
        return new RagdollPosePacket(ragdoll.getId(), ax, ay, az, positions, rotations);
    }

    public RagdollPosePacket(FriendlyByteBuf buf) {
        entityId = buf.readVarInt();
        anchorX = buf.readDouble();
        anchorY = buf.readDouble();
        anchorZ = buf.readDouble();
        positions = new Vector3f[RagdollEntity.PART_COUNT];
        rotations = new Quaternionf[RagdollEntity.PART_COUNT];

        for (int i = 0; i < RagdollEntity.PART_COUNT; i++) {
            positions[i] = new Vector3f(buf.readFloat(), buf.readFloat(), buf.readFloat());
            rotations[i] = new Quaternionf(
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeDouble(anchorX);
        buf.writeDouble(anchorY);
        buf.writeDouble(anchorZ);
        for (int i = 0; i < RagdollEntity.PART_COUNT; i++) {
            buf.writeFloat(positions[i].x);
            buf.writeFloat(positions[i].y);
            buf.writeFloat(positions[i].z);
            buf.writeFloat(rotations[i].x);
            buf.writeFloat(rotations[i].y);
            buf.writeFloat(rotations[i].z);
            buf.writeFloat(rotations[i].w);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        // 经由 DistExecutor 转发，这样纯客户端的处理类
        // 就永远不会在专用服务端上被加载
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPoseHandler.accept(
                        entityId, anchorX, anchorY, anchorZ, positions, rotations)));
        context.get().setPacketHandled(true);
    }
}
