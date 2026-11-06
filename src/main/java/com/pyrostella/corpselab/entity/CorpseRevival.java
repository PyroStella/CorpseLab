package com.pyrostella.corpselab.entity;

import com.pyrostella.corpselab.plan.BodyPlan;
import com.pyrostella.corpselab.plan.PlanRagdoll;
import com.pyrostella.corpselab.ragdoll.RagdollSkeleton;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** 服务器驱动的归位动画，渲染端只插值姿态与每块的缩放 */
public final class CorpseRevival {
    private final Vector3d[] starts;
    private final Quaterniond[] rotations;
    private final boolean[] connected;
    private final Vec3 origin;
    private final float yaw;
    private final int duration;
    private int elapsed;

    public CorpseRevival(PlanRagdoll physics, int base, float yaw, int duration) {
        this.duration = duration;
        this.yaw = yaw;
        this.connected = physics.connectedTo(base);
        this.starts = new Vector3d[physics.bodies().size()];
        this.rotations = new Quaterniond[this.starts.length];
        for (int i = 0; i < starts.length; i++) {
            starts[i] = new Vector3d(physics.body(i).pos);
            rotations[i] = new Quaterniond(physics.body(i).rot);
        }
        var body = physics.body(base);
        Vector3d offset = offset(physics.plan().boxes().get(base));
        this.origin = new Vec3(body.pos.x-offset.x,
                PlanCorpseEntity.blockBounds(body.pos,body.rot,body.halfExtents).minY+0.2D,body.pos.z-offset.z);
    }

    private CorpseRevival(CompoundTag tag, int count) {
        this.duration = Math.max(1,tag.getInt("Duration"));
        this.elapsed = Math.max(0,Math.min(duration,tag.getInt("Elapsed")));
        this.yaw = tag.getFloat("Yaw");
        this.origin = new Vec3(tag.getDouble("X"),tag.getDouble("Y"),tag.getDouble("Z"));
        ListTag parts = tag.getList("Parts",10);
        if (parts.size() != count) throw new IllegalArgumentException("复活姿态部件数量不符");
        this.starts = new Vector3d[count]; this.rotations = new Quaterniond[count]; this.connected = new boolean[count];
        for (int i = 0; i < count; i++) {
            var part = parts.getCompound(i);
            starts[i] = new Vector3d(part.getDouble("X"),part.getDouble("Y"),part.getDouble("Z"));
            rotations[i] = new Quaterniond(part.getDouble("QX"),part.getDouble("QY"),part.getDouble("QZ"),part.getDouble("QW")).normalize();
            connected[i] = part.getBoolean("Connected");
        }
    }

    public static CorpseRevival load(CompoundTag tag,int count) { return new CorpseRevival(tag,count); }
    public Vec3 origin() { return origin; }
    public float yaw() { return yaw; }
    public int elapsed() { return elapsed; }
    public int duration() { return duration; }
    public boolean advance() { elapsed = Math.min(duration,elapsed+1); return elapsed == duration; }
    public float scale(int index) { return connected[index] ? 1 : (float)progress(); }
    private double progress() { double t = (double)elapsed/duration; return t*t*(3-2*t); }
    private Quaterniond facing() { return new Quaterniond().rotateY(RagdollSkeleton.facingFromYaw(yaw)); }
    private Vector3d offset(BodyPlan.Box box) {
        return facing().transform(new Vector3d(-box.cx(),1.501D-box.cy(),box.cz()));
    }

    public void apply(PlanRagdoll physics) {
        double t = progress();
        Quaterniond targetRotation = facing();
        for (BodyPlan.Box box : physics.plan().boxes()) {
            int i = box.index();
            Vector3d target = offset(box).add(origin.x,origin.y,origin.z);
            var body = physics.body(i);
            if (connected[i]) {
                body.pos.set(starts[i]).lerp(target,t);
                body.rot.set(rotations[i]).slerp(targetRotation,t);
            } else { body.pos.set(target); body.rot.set(targetRotation); }
            body.linVel.zero(); body.angVel.zero();
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Duration",duration); tag.putInt("Elapsed",elapsed); tag.putFloat("Yaw",yaw);
        tag.putDouble("X",origin.x); tag.putDouble("Y",origin.y); tag.putDouble("Z",origin.z);
        ListTag parts = new ListTag();
        for (int i = 0; i < starts.length; i++) {
            CompoundTag part = new CompoundTag();
            part.putDouble("X",starts[i].x); part.putDouble("Y",starts[i].y); part.putDouble("Z",starts[i].z);
            part.putDouble("QX",rotations[i].x); part.putDouble("QY",rotations[i].y);
            part.putDouble("QZ",rotations[i].z); part.putDouble("QW",rotations[i].w);
            part.putBoolean("Connected",connected[i]); parts.add(part);
        }
        tag.put("Parts",parts); return tag;
    }
}