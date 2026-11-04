package com.pyrostella.corpselab.client;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pyrostella.corpselab.entity.PlanCorpseEntity;
import com.pyrostella.corpselab.entity.PoseBuffer;
import com.pyrostella.corpselab.plan.BodyPlan;
import com.pyrostella.corpselab.plan.ModelPartScanner.PartRef;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Quaternionf;

/**
 * 尸体渲染器：把每个刚体的位姿套回原模型的对应部件。
 *
 * <p>做法是"逐部件独立绘制"：临时清掉部件的父子关系与自身变换，把它当成一组孤立的长方体，
 * 摆到刚体所在的位置。这样不需要在层级里反解局部变换，也不会出现子部件被画两次。</p>
 */
public class PlanCorpseRenderer extends EntityRenderer<PlanCorpseEntity> {

    private static final ResourceLocation FALLBACK =
            new ResourceLocation("minecraft", "textures/entity/pig/pig.png");

    public PlanCorpseRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void render(PlanCorpseEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        PoseBuffer poses = entity.poses();
        BodyPlan plan = entity.plan();
        if (poses == null || !poses.hasPose() || plan.boxCount() == 0) {
            return;
        }

        EntityType<?> sourceType = ForgeRegistries.ENTITY_TYPES.getValue(entity.sourceType());
        PlanModelCache.Binding binding = sourceType == null ? null : PlanModelCache.get(sourceType);
        if (binding == null || binding.parts().size() != plan.boxCount()) {
            // 拿不到模型，或者模型结构与骨架对不上（例如换了资源包）：这一帧不画
            return;
        }

        poses.sample(partialTick);

        ResourceLocation texture;
        try {
            texture = ((LivingEntityRenderer) binding.renderer()).getTextureLocation(binding.probe());
        } catch (Exception e) {
            texture = FALLBACK;
        }

        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(texture));

        for (BodyPlan.Box box : plan.boxes()) {
            PartRef ref = binding.parts().get(box.index());
            var position = poses.position(box.index());
            Quaternionf rotation = poses.rotation(box.index(), new Quaternionf());

            poseStack.pushPose();
            poseStack.translate(position.x - entity.getX(),
                    position.y - entity.getY(),
                    position.z - entity.getZ());
            poseStack.mulPose(rotation);
            // 进入原版模型空间：Y 向下、X 左右相反。两次翻转不改变手性，仍是正常旋转
            poseStack.scale(-1.0F, -1.0F, 1.0F);
            // 该部件的方块是以 cuboid 中心为原点建模的，抵消掉才能让中心落在刚体位置
            poseStack.translate(-ref.cx(), -ref.cy(), -ref.cz());

            renderPartAlone(poseStack, consumer, ref.part(), packedLight);

            poseStack.popPose();
        }
    }

    /**
     * 只画这一个部件的方块，不画子部件、不带自身变换。
     *
     * <p>父级的平移到这一步已经由刚体位姿表达过了，所以必须先归零，否则会被算两遍。</p>
     */
    private static void renderPartAlone(PoseStack poseStack, VertexConsumer consumer,
                                        ModelPart part, int packedLight) {
        float savedX = part.x;
        float savedY = part.y;
        float savedZ = part.z;
        float savedXRot = part.xRot;
        float savedYRot = part.yRot;
        float savedZRot = part.zRot;
        boolean savedSkip = part.skipDraw;
        Map<String, ModelPart> savedChildren = new HashMap<>(part.children);

        try {
            part.x = 0.0F;
            part.y = 0.0F;
            part.z = 0.0F;
            part.xRot = 0.0F;
            part.yRot = 0.0F;
            part.zRot = 0.0F;
            part.skipDraw = false;
            part.children.clear();

            part.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
        } finally {
            part.x = savedX;
            part.y = savedY;
            part.z = savedZ;
            part.xRot = savedXRot;
            part.yRot = savedYRot;
            part.zRot = savedZRot;
            part.skipDraw = savedSkip;
            part.children.putAll(savedChildren);
        }
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
