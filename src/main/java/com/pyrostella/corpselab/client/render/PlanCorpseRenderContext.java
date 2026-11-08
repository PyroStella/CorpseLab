package com.pyrostella.corpselab.client.render;

import com.pyrostella.corpselab.api.client.CorpseRenderContext;
import com.pyrostella.corpselab.api.model.BodyPlan;
import com.pyrostella.corpselab.internal.corpse.PlanCorpseEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** 将内部尸体适配为公开绘制能力，仅在姿态采样完成后构造 */
public record PlanCorpseRenderContext(PlanCorpseEntity corpse) implements CorpseRenderContext {
    @Override public Entity entity() { return corpse; }
    @Override public BodyPlan plan() { return corpse.plan(); }
    @Override public ResourceLocation sourceType() { return corpse.sourceType(); }
    @Override public CompoundTag appearance() { return corpse.appearance(); }
    @Override public Vec3 partPosition(int index) { return corpse.poses().position(index); }
    @Override public Quaternionf partRotation(int index, Quaternionf destination) {
        return corpse.poses().rotation(index, destination);
    }
    @Override public float partScale(int index) { return corpse.poses().scale(index); }
}
