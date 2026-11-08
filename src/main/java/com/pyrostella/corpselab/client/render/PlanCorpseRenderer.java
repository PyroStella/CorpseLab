package com.pyrostella.corpselab.client.render;

import com.pyrostella.corpselab.client.compat.PoseCompat;
import com.pyrostella.corpselab.client.model.PlanModelCache;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pyrostella.corpselab.api.client.CorpseModelAdapter;
import com.pyrostella.corpselab.internal.corpse.PlanCorpseEntity;
import com.pyrostella.corpselab.client.animation.PoseBuffer;
import com.pyrostella.corpselab.api.model.BodyPlan;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Quaternionf;

/**
 * 尸体渲染器：把每个刚体的位姿套回原模型的对应部件
 *
 * <p>只负责物理姿态到世界坐标的转换，具体部件、材质和局部模型变换由模型适配器处理。</p>
 */
public class PlanCorpseRenderer extends EntityRenderer<PlanCorpseEntity> {

    private static final ResourceLocation FALLBACK = new ResourceLocation("minecraft", "textures/entity/pig/pig.png");

    public PlanCorpseRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(PlanCorpseEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        PoseBuffer poses = entity.poses();
        BodyPlan plan = entity.plan();
        if (poses == null || !poses.hasPose() || plan.boxCount() == 0) {
            return;
        }

        EntityType<?> sourceType = ForgeRegistries.ENTITY_TYPES.getValue(entity.sourceType());
        CorpseModelAdapter.Binding binding = sourceType == null ? null : PlanModelCache.get(entity, sourceType);
        if (binding == null) {
            return;
        }

        poses.sample(partialTick);
        entity.updateClientBounds();

        var context = new PlanCorpseRenderContext(entity);
        CorpseModelAdapter.PartRenderer parts = binding.prepare(context, bufferSource, partialTick);
        double renderX = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double renderY = Mth.lerp(partialTick, entity.yOld, entity.getY());
        double renderZ = Mth.lerp(partialTick, entity.zOld, entity.getZ());

        for (BodyPlan.Box box : plan.boxes()) {
            float scale = poses.scale(box.index());
            if (scale <= 0) continue;
            var position = poses.position(box.index());
            Quaternionf rotation = poses.rotation(box.index(), new Quaternionf());

            poseStack.pushPose();
            try {
                poseStack.translate(position.x - renderX,
                        position.y - renderY,
                        position.z - renderZ);
                PoseCompat.mulPose(poseStack, rotation);
                poseStack.scale(scale,scale,scale);
                parts.render(box, poseStack, packedLight);
            } finally {
                poseStack.popPose();
            }
        }
        binding.renderLayers(context, poseStack, bufferSource, packedLight, partialTick);
    }

    @Override
    public ResourceLocation getTextureLocation(PlanCorpseEntity entity) {
        return FALLBACK;
    }

    /** 部件可能伸出实体包围盒，关掉视锥剔除 */
    @Override
    public boolean shouldRender(PlanCorpseEntity entity,
                                net.minecraft.client.renderer.culling.Frustum frustum,
                                double camX, double camY, double camZ) {
        return entity.distanceToSqr(camX, camY, camZ) < 96.0D * 96.0D;
    }
}
