package com.pyrostella.corpselab.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pyrostella.corpselab.client.ModelRenderPlan;
import com.pyrostella.corpselab.plan.BodyPlan;
import com.pyrostella.corpselab.plan.ModelPartScanner;
import com.pyrostella.corpselab.entity.PlanCorpseEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;

/** 原有扫描、实例缩放和独立部件绘制逻辑的默认适配实现 */
final class VanillaModelAdapter implements CorpseModelAdapter {
    private static final ResourceLocation FALLBACK = new ResourceLocation("minecraft", "textures/entity/pig/pig.png");

    @Override
    public boolean supports(LivingEntity entity, EntityRenderer<?> renderer) {
        return renderer instanceof LivingEntityRenderer<?, ?>;
    }

    @Override
    public boolean usesLivingRenderEvent() {
        return true;
    }

    @Override
    public Object modelKey(LivingEntity entity, EntityRenderer<?> renderer) {
        return ((LivingEntityRenderer<?, ?>) renderer).getModel();
    }

    @Override
    public Scan scan(LivingEntity entity, EntityRenderer<?> renderer) {
        EntityModel<?> model = ((LivingEntityRenderer<?, ?>) renderer).getModel();
        ModelPartScanner.ScanResult result = ModelPartScanner.scanDetailed(model);
        return new Scan() {
            @Override
            public BodyPlan plan() {
                return result.plan();
            }

            @Override
            public BodyPlan capture(LivingEntity source, EntityRenderer<?> sourceRenderer, float partialTick) {
                return ModelRenderPlan.capture(result, model,
                        (LivingEntityRenderer<?, ?>) sourceRenderer, source, partialTick);
            }
        };
    }

    @Override
    public Binding bind(LivingEntity probe, EntityRenderer<?> renderer) {
        LivingEntityRenderer<?, ?> livingRenderer = (LivingEntityRenderer<?, ?>) renderer;
        // 完整部件表不受共享活体模型当前可见性的影响
        ModelPartScanner.ScanResult scan = ModelPartScanner.scanDetailed(livingRenderer.getModel(), true);
        if (scan.parts().isEmpty()) {
            return null;
        }
        Map<String, BodyPlan.Box> byPath = new HashMap<>();
        for (BodyPlan.Box box : scan.plan().boxes()) {
            byPath.put(box.path(), box);
        }
        return new Binding() {
          @Override
          public PartRenderer prepare(PlanCorpseEntity corpse, MultiBufferSource buffers, float partialTick) {
            ResourceLocation texture = texture(livingRenderer, probe);
            VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
            return (box, stack, packedLight) -> {
                BodyPlan.Box modelBox = byPath.get(box.path());
                if (modelBox == null) {
                    return;
                }
                ModelPartScanner.PartRef ref = scan.parts().get(modelBox.index());
                stack.scale(-1.0F, -1.0F, 1.0F);
                stack.scale(scale(box.hx(), modelBox.hx()), scale(box.hy(), modelBox.hy()),
                        scale(box.hz(), modelBox.hz()));
                stack.translate(-ref.cx(), -ref.cy(), -ref.cz());
                renderPartAlone(stack, consumer, ref.part(), packedLight);
            };
          }

          @Override
          public void renderLayers(PlanCorpseEntity corpse, PoseStack stack, MultiBufferSource buffers,
                                   int light, float partialTick) {
              CorpseLayers.render(livingRenderer, probe, corpse, stack, buffers, light, partialTick);
          }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ResourceLocation texture(LivingEntityRenderer renderer, LivingEntity probe) {
        try {
            return renderer.getTextureLocation(probe);
        } catch (Exception failure) {
            return FALLBACK;
        }
    }

    private static float scale(double actual, double model) {
        return model > 1.0E-6D ? (float) (actual / model) : 1.0F;
    }

    /** 临时隔离部件，恢复共享模型的全部原有状态 */
    private static void renderPartAlone(PoseStack stack, VertexConsumer consumer, ModelPart part, int light) {
        float x = part.x;
        float y = part.y;
        float z = part.z;
        float xRot = part.xRot;
        float yRot = part.yRot;
        float zRot = part.zRot;
        boolean skip = part.skipDraw;
        boolean visible = part.visible;
        Map<String, ModelPart> children = new HashMap<>(part.children);
        try {
            part.x = part.y = part.z = 0.0F;
            part.xRot = part.yRot = part.zRot = 0.0F;
            part.skipDraw = false;
            part.visible = true;
            part.children.clear();
            part.render(stack, consumer, light, OverlayTexture.NO_OVERLAY);
        } finally {
            part.x = x;
            part.y = y;
            part.z = z;
            part.xRot = xRot;
            part.yRot = yRot;
            part.zRot = zRot;
            part.skipDraw = skip;
            part.visible = visible;
            part.children.putAll(children);
        }
    }
}
