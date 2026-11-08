package com.pyrostella.corpselab.client.sync;

import com.pyrostella.corpselab.internal.corpse.RagdollEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 把收到的姿态快照应用到它所属的布娃娃上 */
public final class ClientPoseHandler {

    private ClientPoseHandler() {}

    public static void accept(int entityId, double anchorX, double anchorY, double anchorZ,
                              Vector3f[] positions, Quaternionf[] rotations) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Entity entity = level.getEntity(entityId);
        if (entity instanceof RagdollEntity ragdoll) {
            ragdoll.applyPose(anchorX, anchorY, anchorZ, positions, rotations);
        }
    }
}
