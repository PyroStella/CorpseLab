package com.pyrostella.corpselab.internal.corpse;

import com.pyrostella.corpselab.physics.RigidBody;
import com.pyrostella.corpselab.api.model.BodyPart;
import com.pyrostella.corpselab.internal.skeleton.RagdollSkeleton;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * 保存并恢复一具身体停稳时摆出的姿态
 *
 * <p>没有它的话，重新加载的身体会站起来，因为新建的骨架总是
 * 按休息姿态构建的
 */
public final class BodyPose {

    private BodyPose() {
    }

    public static void write(CompoundTag tag, RagdollSkeleton skeleton) {
        if (skeleton == null) {
            return;
        }
        ListTag pose = new ListTag();
        for (BodyPart part : BodyPart.values()) {
            RigidBody body = skeleton.part(part);
            CompoundTag entry = new CompoundTag();
            entry.putDouble("X", body.pos.x);
            entry.putDouble("Y", body.pos.y);
            entry.putDouble("Z", body.pos.z);
            entry.putFloat("QX", (float) body.rot.x);
            entry.putFloat("QY", (float) body.rot.y);
            entry.putFloat("QZ", (float) body.rot.z);
            entry.putFloat("QW", (float) body.rot.w);
            pose.add(entry);
        }
        tag.put("Pose", pose);
    }

    public static void read(CompoundTag tag, RagdollSkeleton skeleton) {
        ListTag pose = tag.getList("Pose", 10);
        for (int i = 0; i < pose.size() && i < BodyPart.values().length; i++) {
            CompoundTag entry = pose.getCompound(i);
            RigidBody body = skeleton.part(BodyPart.values()[i]);
            body.pos.set(entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z"));
            body.rot.set(entry.getFloat("QX"), entry.getFloat("QY"),
                    entry.getFloat("QZ"), entry.getFloat("QW"));
        }
    }
}
