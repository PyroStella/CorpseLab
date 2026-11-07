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

/** 服务端 → 客户端：一帧尸体姿态 */
public final class PlanPosePacket {

    private final int entityId;
    private final double anchorX;
    private final double anchorY;
    private final double anchorZ;
    private final Vec3[] offsets;
    private final Quaternionf[] rotations;
    private final float[] scales;
    private final boolean[] regrowing;

    private PlanPosePacket(int entityId, double anchorX, double anchorY, double anchorZ,
                           Vec3[] offsets, Quaternionf[] rotations,float[] scales,boolean[] regrowing) {
        this.entityId = entityId;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.offsets = offsets;
        this.rotations = rotations;
        this.scales = scales;
        this.regrowing = regrowing;
    }

    public static PlanPosePacket of(PlanCorpseEntity entity) {
        List<RigidBody> bodies = entity.rigidBodies();
        Vec3[] offsets = new Vec3[bodies.size()];
        Quaternionf[] rotations = new Quaternionf[bodies.size()];
        float[] scales = new float[bodies.size()];
        boolean[] regrowing = new boolean[bodies.size()];
        double anchorX = entity.getX();
        double anchorY = entity.getY();
        double anchorZ = entity.getZ();

        for (int i = 0; i < bodies.size(); i++) {
            RigidBody body = bodies.get(i);
            scales[i] = entity.limbScale(i);
            regrowing[i] = entity.limbRegrowing(i);
            offsets[i] = new Vec3(
                    body.pos.x - anchorX,
                    body.pos.y - anchorY,
                    body.pos.z - anchorZ);
            rotations[i] = new Quaternionf(
                    (float) body.rot.x, (float) body.rot.y,
                    (float) body.rot.z, (float) body.rot.w);
        }

        return new PlanPosePacket(entity.getId(), anchorX, anchorY, anchorZ, offsets, rotations,scales,regrowing);
    }

    public PlanPosePacket(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
        this.anchorX = buf.readDouble();
        this.anchorY = buf.readDouble();
        this.anchorZ = buf.readDouble();
        int count = com.pyrostella.corpselab.util.PacketReads.count(buf, 33);
        this.offsets = new Vec3[count];
        this.rotations = new Quaternionf[count];
        this.scales = new float[count];
        this.regrowing = new boolean[count];
        for (int i = 0; i < count; i++) {
            this.offsets[i] = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
            this.rotations[i] = new Quaternionf(
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
            this.scales[i] = buf.readFloat();
            this.regrowing[i] = buf.readBoolean();
            if (!Float.isFinite(this.scales[i]) || this.scales[i] < 0)
                throw new io.netty.handler.codec.DecoderException("无效的复活缩放");
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeDouble(this.anchorX);
        buf.writeDouble(this.anchorY);
        buf.writeDouble(this.anchorZ);
        buf.writeVarInt(this.offsets.length);
        for (int i = 0; i < this.offsets.length; i++) {
            buf.writeFloat((float) this.offsets[i].x);
            buf.writeFloat((float) this.offsets[i].y);
            buf.writeFloat((float) this.offsets[i].z);
            buf.writeFloat(this.rotations[i].x);
            buf.writeFloat(this.rotations[i].y);
            buf.writeFloat(this.rotations[i].z);
            buf.writeFloat(this.rotations[i].w);
            buf.writeFloat(this.scales[i]);
            buf.writeBoolean(this.regrowing[i]);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PlanCorpseClientHandler.acceptPose(
                        this.entityId, this.anchorX, this.anchorY, this.anchorZ,
                        this.offsets, this.rotations,this.scales,this.regrowing)));
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
