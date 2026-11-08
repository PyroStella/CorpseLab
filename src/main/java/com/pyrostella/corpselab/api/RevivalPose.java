package com.pyrostella.corpselab.api;

import com.pyrostella.corpselab.api.model.BodyPlan;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import java.util.*;

/** 完全脱离模型运行时的目标姿态，可保存或通过附属自己的数据包传输 */
public record RevivalPose(Map<String, Bone> bones) {
    public RevivalPose { bones = Map.copyOf(bones); }
    /** 身体局部坐标，脚底为原点、Y 向上；位置为部件中心，旋转相对尸体绑定几何 */
    public record Bone(Vec3 position, CorpseView.Rotation rotation, float scale) {
        public Bone {
            Objects.requireNonNull(position); Objects.requireNonNull(rotation);
            double length = rotation.x()*rotation.x()+rotation.y()*rotation.y()+rotation.z()*rotation.z()+rotation.w()*rotation.w();
            if (!Float.isFinite((float)position.x) || !Float.isFinite((float)position.y) || !Float.isFinite((float)position.z)
                    || !Double.isFinite(position.lengthSqr()) || !Double.isFinite(length) || Math.abs(length-1) > 0.001
                    || !Float.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("无效的目标部件姿态");
        }
    }
    public static RevivalPose empty() { return new RevivalPose(Map.of()); }
    public static RevivalPose rest(BodyPlan plan) {
        var parts = new LinkedHashMap<String, Bone>();
        for (var box : plan.boxes()) parts.put(box.path(), rest(box));
        return new RevivalPose(parts);
    }
    public static Bone rest(BodyPlan.Box box) {
        return new Bone(new Vec3(-box.cx(), 1.501-box.cy(), box.cz()), new CorpseView.Rotation(0,0,0,1), 1);
    }
    public RevivalPose bone(String id, Vec3 position, Quaterniondc rotation) { return bone(id, position, rotation, 1); }
    public RevivalPose bone(String id, Vec3 position, Quaterniondc rotation, float scale) {
        var parts = new LinkedHashMap<>(bones);
        parts.put(id, new Bone(position, new CorpseView.Rotation(rotation.x(),rotation.y(),rotation.z(),rotation.w()), scale));
        return new RevivalPose(parts);
    }
    /** 缺省部件回退到默认姿态；未知或有歧义的部件 ID 拒绝执行 */
    public boolean matches(BodyPlan skeleton) {
        var ids = new HashSet<String>();
        for (var box : skeleton.boxes()) if (!ids.add(box.path())) return false;
        return ids.containsAll(bones.keySet());
    }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); ListTag list = new ListTag();
        bones.forEach((id, bone) -> {
            CompoundTag part = new CompoundTag(); part.putString("Id", id);
            part.putDouble("X",bone.position.x); part.putDouble("Y",bone.position.y); part.putDouble("Z",bone.position.z);
            part.putDouble("QX",bone.rotation.x()); part.putDouble("QY",bone.rotation.y());
            part.putDouble("QZ",bone.rotation.z()); part.putDouble("QW",bone.rotation.w()); part.putFloat("Scale",bone.scale);
            list.add(part);
        });
        tag.put("Bones", list); return tag;
    }
    public static RevivalPose load(CompoundTag tag) {
        var bones = new LinkedHashMap<String, Bone>();
        for (var item : tag.getList("Bones",10)) {
            CompoundTag part = (CompoundTag)item;
            String id = part.getString("Id");
            Bone bone = new Bone(new Vec3(part.getDouble("X"),part.getDouble("Y"),part.getDouble("Z")),
                    new CorpseView.Rotation(part.getDouble("QX"),part.getDouble("QY"),part.getDouble("QZ"),part.getDouble("QW")),part.getFloat("Scale"));
            if (id.isBlank() || bones.putIfAbsent(id,bone) != null) throw new IllegalArgumentException("重复或空部件 ID");
        }
        return new RevivalPose(bones);
    }
}
