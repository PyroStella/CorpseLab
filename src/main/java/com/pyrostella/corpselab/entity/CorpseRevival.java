package com.pyrostella.corpselab.entity;

import com.pyrostella.corpselab.plan.BodyPlan;
import com.pyrostella.corpselab.plan.PlanRagdoll;
import com.pyrostella.corpselab.ragdoll.RagdollSkeleton;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** 服务器驱动的归位动画，渲染端只插值姿态与每块的缩放 */
public final class CorpseRevival {
    private static final double RETURN_DISTANCE_SQUARED = 2.0D * 2.0D;

    private final Vector3d[] starts;
    private final Quaterniond[] rotations;
    private final boolean[] returnsToBody;
    private final Vector3d[] targets;
    private final Quaterniond[] targetRotations;
    private final float[] targetScales;
    private final Vec3 origin;
    private final float yaw;
    private final int duration;
    private final int base;
    private final java.util.UUID id;
    private int elapsed;

    public CorpseRevival(PlanRagdoll physics, int base, float yaw, int duration) {
        this(physics, base, yaw, duration, com.pyrostella.corpselab.api.RevivalPose.rest(physics.plan()));
    }

    public CorpseRevival(PlanRagdoll physics, int base, float yaw, int duration,
                         com.pyrostella.corpselab.api.RevivalPose pose) {
        this(physics,base,yaw,duration,pose,java.util.UUID.randomUUID());
    }
    public CorpseRevival(PlanRagdoll physics,int base,float yaw,int duration,
                         com.pyrostella.corpselab.api.RevivalPose pose,java.util.UUID id) {
        this.id=id;
        this.duration = duration;
        this.base = base;
        this.yaw = yaw;
        this.returnsToBody = new boolean[physics.bodies().size()];
        var baseBody = physics.body(base);
        AABB baseBounds = PlanCorpseEntity.blockBounds(baseBody.pos, baseBody.rot, baseBody.halfExtents);
        this.starts = new Vector3d[physics.bodies().size()];
        this.rotations = new Quaterniond[this.starts.length];
        this.targets = new Vector3d[this.starts.length];
        this.targetRotations = new Quaterniond[this.starts.length];
        this.targetScales = new float[this.starts.length];
        for (int i = 0; i < starts.length; i++) {
            var part = physics.body(i);
            AABB bounds = PlanCorpseEntity.blockBounds(part.pos, part.rot, part.halfExtents);
            // 复活开始时按两个世界 AABB 的最短距离分类，边界两格也归位
            returnsToBody[i] = distanceSquared(baseBounds, bounds) <= RETURN_DISTANCE_SQUARED;
            starts[i] = new Vector3d(part.pos);
            rotations[i] = new Quaterniond(physics.body(i).rot);
        }
        var body = physics.body(base);
        var baseBox = physics.plan().boxes().get(base);
        var baseTarget = pose.bones().getOrDefault(baseBox.path(), com.pyrostella.corpselab.api.RevivalPose.rest(baseBox));
        Vector3d offset = facing().transform(new Vector3d(baseTarget.position().x,baseTarget.position().y,baseTarget.position().z));
        this.origin = new Vec3(body.pos.x-offset.x,
                PlanCorpseEntity.blockBounds(body.pos,body.rot,body.halfExtents).minY+0.2D,body.pos.z-offset.z);
        for (var box : physics.plan().boxes()) {
            int i = box.index();
            var target = pose.bones().getOrDefault(box.path(), com.pyrostella.corpselab.api.RevivalPose.rest(box));
            targets[i] = facing().transform(new Vector3d(target.position().x,target.position().y,target.position().z))
                    .add(origin.x,origin.y,origin.z);
            var rotation = target.rotation();
            targetRotations[i] = facing().mul(new Quaterniond(rotation.x(),rotation.y(),rotation.z(),rotation.w()));
            targetScales[i] = target.scale();
        }
    }

    private static double distanceSquared(AABB a, AABB b) {
        double dx = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
        double dy = Math.max(0, Math.max(a.minY - b.maxY, b.minY - a.maxY));
        double dz = Math.max(0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    private CorpseRevival(CompoundTag tag, int count) {
        this.id = tag.getUUID("Attempt");
        this.base = tag.getInt("Base");
        this.duration = Math.max(1,tag.getInt("Duration"));
        this.elapsed = Math.max(0,Math.min(duration,tag.getInt("Elapsed")));
        this.yaw = tag.getFloat("Yaw");
        this.origin = new Vec3(tag.getDouble("X"),tag.getDouble("Y"),tag.getDouble("Z"));
        ListTag parts = tag.getList("Parts",10);
        if (parts.size() != count) throw new IllegalArgumentException("复活姿态部件数量不符");
        this.starts = new Vector3d[count]; this.rotations = new Quaterniond[count]; this.returnsToBody = new boolean[count];
        this.targets = new Vector3d[count]; this.targetRotations = new Quaterniond[count]; this.targetScales = new float[count];
        for (int i = 0; i < count; i++) {
            var part = parts.getCompound(i);
            starts[i] = new Vector3d(part.getDouble("X"),part.getDouble("Y"),part.getDouble("Z"));
            rotations[i] = new Quaterniond(part.getDouble("QX"),part.getDouble("QY"),part.getDouble("QZ"),part.getDouble("QW")).normalize();
            returnsToBody[i] = part.getBoolean("ReturnToBody");
            if (!part.contains("TX")) throw new IllegalArgumentException("复活存档缺少目标姿态");
            targets[i] = new Vector3d(part.getDouble("TX"),part.getDouble("TY"),part.getDouble("TZ"));
            targetRotations[i] = new Quaterniond(part.getDouble("TQX"),part.getDouble("TQY"),part.getDouble("TQZ"),part.getDouble("TQW")).normalize();
            targetScales[i] = part.getFloat("TargetScale");
        }
    }

    public static CorpseRevival load(CompoundTag tag,int count) { return new CorpseRevival(tag,count); }
    public java.util.UUID id() { return id; }
    public int base() { return base; }
    public Vec3 origin() { return origin; }
    public float yaw() { return yaw; }
    public int elapsed() { return elapsed; }
    public int duration() { return duration; }
    public boolean advance() { elapsed = Math.min(duration,elapsed+1); return elapsed == duration; }
    public boolean regrowing(int index) { return !returnsToBody[index]; }
    public float scale(int index) { return returnsToBody[index] ? 1+(targetScales[index]-1)*(float)progress() : targetScales[index]*(float)progress(); }
    private double progress() { double t = (double)elapsed/duration; return t*t*(3-2*t); }
    private Quaterniond facing() { return new Quaterniond().rotateY(RagdollSkeleton.facingFromYaw(yaw)); }
    public void apply(PlanRagdoll physics) {
        double t = progress();
        for (BodyPlan.Box box : physics.plan().boxes()) {
            int i = box.index();
            Vector3d target = targets[i];
            Quaterniond targetRotation = targetRotations[i];
            var body = physics.body(i);
            if (returnsToBody[i]) {
                body.pos.set(starts[i]).lerp(target,t);
                body.rot.set(rotations[i]).slerp(targetRotation,t);
            } else { body.pos.set(target); body.rot.set(targetRotation); }
            body.linVel.zero(); body.angVel.zero();
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Attempt",id);
        tag.putInt("Base",base);
        tag.putInt("Duration",duration); tag.putInt("Elapsed",elapsed); tag.putFloat("Yaw",yaw);
        tag.putDouble("X",origin.x); tag.putDouble("Y",origin.y); tag.putDouble("Z",origin.z);
        ListTag parts = new ListTag();
        for (int i = 0; i < starts.length; i++) {
            CompoundTag part = new CompoundTag();
            part.putDouble("X",starts[i].x); part.putDouble("Y",starts[i].y); part.putDouble("Z",starts[i].z);
            part.putDouble("QX",rotations[i].x); part.putDouble("QY",rotations[i].y);
            part.putDouble("QZ",rotations[i].z); part.putDouble("QW",rotations[i].w);
            part.putBoolean("ReturnToBody",returnsToBody[i]);
            part.putDouble("TX",targets[i].x); part.putDouble("TY",targets[i].y); part.putDouble("TZ",targets[i].z);
            part.putDouble("TQX",targetRotations[i].x); part.putDouble("TQY",targetRotations[i].y);
            part.putDouble("TQZ",targetRotations[i].z); part.putDouble("TQW",targetRotations[i].w);
            part.putFloat("TargetScale",targetScales[i]); parts.add(part);
        }
        tag.put("Parts",parts); return tag;
    }
}