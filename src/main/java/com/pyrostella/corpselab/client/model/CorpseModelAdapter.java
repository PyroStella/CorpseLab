package com.pyrostella.corpselab.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pyrostella.corpselab.entity.PlanCorpseEntity;
import com.pyrostella.corpselab.plan.BodyPlan;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/** 客户端模型体系的适配入口，不要求使用原版 ModelPart 或 LivingEntityRenderer */
public interface CorpseModelAdapter {
    boolean supports(LivingEntity entity, EntityRenderer<?> renderer);

    /** 模型结构变化时必须返回不同的标识，标识按实例身份比较 */
    Object modelKey(LivingEntity entity, EntityRenderer<?> renderer);

    Scan scan(LivingEntity entity, EntityRenderer<?> renderer);

    /** 暂时无法绑定时返回 null；绑定中的部件路径必须与扫描结果一致 */
    @Nullable
    Binding bind(LivingEntity probe, EntityRenderer<?> renderer);

    /** 原版在活体渲染事件中采样，其它适配器默认每五 tick 从实体状态采样 */
    default boolean usesLivingRenderEvent() {
        return false;
    }

    interface Scan {
        BodyPlan plan();

        /** 必须保留 plan 的部件路径、顺序和关节，只改变当前实例的尺寸 */
        BodyPlan capture(LivingEntity entity, EntityRenderer<?> renderer, float partialTick);
    }

    interface Binding {
        /** 每具尸体绘制前调用一次，适配器可自行选择贴图、渲染类型及缓冲 */
        PartRenderer prepare(PlanCorpseEntity corpse, MultiBufferSource buffers, float partialTick);
    }

    @FunctionalInterface
    interface PartRenderer {
        /** 输入栈已包含该块的世界位置和旋转，局部模型变换由适配器负责 */
        void render(BodyPlan.Box box, PoseStack stack, int packedLight);
    }
}
