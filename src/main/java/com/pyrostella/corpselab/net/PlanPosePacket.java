package com.pyrostella.corpselab.net;

import java.util.List;
import java.util.function.Supplier;

import org.joml.Quaternionf;

import com.pyrostella.corpselab.client.PlanCorpseClientHandler;
import com.pyrostella.corpselab.entity.PlanCorpseEntity;
import com.pyrostella.corpselab.physics.RigidBody;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** 服务端 → 客户端：一帧尸体姿态。 */
public final class PlanPosePacket {

    private final int entityId;
    private final Vec3[] positions;
    private final Quaternionf[] rotations;

    private PlanPosePacket(int entityId, Vec3[] positions, Quaternionf[] rotations) {
        this.entityId = entityId;
        this.positions = positions;
        this.rotations = rotations;
    }

    public static PlanPosePacket of(PlanCorpseEntity entity) {
        List<RigidBody> bodies = entity.rigidBodies();
        Vec3[] positions = new Vec3[bodies.size()];
        Quaternionf[] rotations = new Quaternionf[bodies.size()];

        for (int i = 0; i < bodies.size(); i++) {
            RigidBody body = bodies.get(i);
            positions[i] = new Vec3(body.pos.x, body.pos.y, body.pos.z);
            rotations[i] = new Quaternionf(
                    (float) body.rot.x, (float) body.rot.y,
                    (float) body.rot.z, (float) body.rot.w);
        }

        return new PlanPosePacket(entity.getId(), positions, rotations);
    }

    public PlanPosePacket(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
        int count = buf.readVarInt();
        this.positions = new Vec3[count];
        this.rotations = new Quaternionf[count];
        for (int i = 0; i < count; i++) {
            this.positions[i] = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
            this.rotations[i] = new Quaternionf(
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeVarInt(this.positions.length);
        for (int i = 0; i < this.positions.length; i++) {
            buf.writeFloat((float) this.positions[i].x);
            buf.writeFloat((float) this.positions[i].y);
            buf.writeFloat((float) this.positions[i].z);
            buf.writeFloat(this.rotations[i].x);
            buf.writeFloat(this.rotations[i].y);
            buf.writeFloat(this.rotations[i].z);
            buf.writeFloat(this.rotations[i].w);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PlanCorpseClientHandler.acceptPose(
                        this.entityId, this.positions, this.rotations)));
        context.get().setPacketHandled(true);
    }

    /** 客户端查找实体用 */
    public static PlanCorpseEntity find(Minecraft minecraft, int entityId) {
        if (minecraft.level == null) {
            return null;
        }
        return minecraft.level.getEntity(entityId) instanceof PlanCorpseEntity corpse ? corpse : null;
    }
}
