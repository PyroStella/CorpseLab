package com.pyrostella.corpselab.client;

import org.joml.Quaternionf;

import com.pyrostella.corpselab.entity.PlanCorpseEntity;
import com.pyrostella.corpselab.plan.BodyPlan;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** 客户端收包入口：只做"找到实体、把数据塞进去"，不含渲染逻辑 */
public final class PlanCorpseClientHandler {

    private PlanCorpseClientHandler() {
    }

    public static void acceptPlan(int entityId, ResourceLocation sourceType, BodyPlan plan) {
        PlanCorpseEntity corpse = find(entityId);
        if (corpse != null) {
            corpse.setSourceType(sourceType);
            corpse.setPlan(plan);
        }
    }

    public static void acceptPose(int entityId, double anchorX, double anchorY, double anchorZ,
                                  Vec3[] offsets, Quaternionf[] rotations) {
        PlanCorpseEntity corpse = find(entityId);
        if (corpse != null) {
            corpse.acceptPose(anchorX, anchorY, anchorZ, offsets, rotations);
        }
    }

    private static PlanCorpseEntity find(int entityId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        return minecraft.level.getEntity(entityId) instanceof PlanCorpseEntity corpse ? corpse : null;
    }
}
