package com.pyrostella.corpselab.api.client;

import com.pyrostella.corpselab.api.model.BodyPlan;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** 当前绘制帧的只读能力；部件姿态已完成插值，不向附属暴露内部实体或缓冲器 */
public interface CorpseRenderContext {
    Entity entity();
    BodyPlan plan();
    ResourceLocation sourceType();
    /** 返回独立副本，修改不会影响尸体或其它渲染器 */
    CompoundTag appearance();
    /** 当前采样的世界坐标 */
    Vec3 partPosition(int index);
    /** 将当前采样旋转写入调用者的目标对象，不返回内部可变四元数 */
    Quaternionf partRotation(int index, Quaternionf destination);
    float partScale(int index);
}
