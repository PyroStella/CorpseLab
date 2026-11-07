package com.pyrostella.corpselab.compat.geckolib;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pyrostella.corpselab.api.client.CorpseModelAdapter;
import com.pyrostella.corpselab.api.client.CorpseRenderContext;
import com.pyrostella.corpselab.api.model.BodyPlan;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.GeoRenderer;

import java.util.HashMap;

/** 仅在 GeckoLib 存在时加载，常规模型及服务端均不引用 GeckoLib 类型 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class GeckoModelAdapter implements CorpseModelAdapter {
    @Override
    public boolean supports(LivingEntity entity, EntityRenderer<?> renderer) {
        return entity instanceof GeoAnimatable && renderer instanceof software.bernie.geckolib.renderer.GeoEntityRenderer;
    }

    private static BakedGeoModel model(LivingEntity entity, EntityRenderer<?> renderer) {
        GeoRenderer geo = (GeoRenderer) renderer;
        return geo.getGeoModel().getBakedModel(geo.getGeoModel().getModelResource((GeoAnimatable) entity, geo));
    }

    @Override
    public Object modelKey(LivingEntity entity, EntityRenderer<?> renderer) {
        return model(entity, renderer);
    }

    private static GeckoModelScan.Result captureModel(LivingEntity entity, EntityRenderer<?> renderer, float partialTick) {
        GeoRenderer geo = (GeoRenderer) renderer;
        BakedGeoModel baked = model(entity, renderer);
        PoseStack stack = new PoseStack();
        // 保留渲染器的幼年、变种和整体尺寸变换，不调用动画更新
        geo.preRender(stack, (GeoAnimatable) entity, baked, null, null, false, partialTick,
                0, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
        return GeckoModelScan.scan(baked, new Matrix4f(stack.last().pose()));
    }

    @Override
    public Scan scan(LivingEntity entity, EntityRenderer<?> renderer) {
        GeckoModelScan.Result base = captureModel(entity, renderer, 0);
        return new Scan() {
            public BodyPlan plan() { return base.plan(); }
            public BodyPlan capture(LivingEntity source, EntityRenderer<?> sourceRenderer, float partialTick) {
                return captureModel(source, sourceRenderer, partialTick).plan();
            }
        };
    }

    @Override
    public Binding bind(LivingEntity probe, EntityRenderer<?> renderer) {
        GeoRenderer geo = (GeoRenderer) renderer;
        GeoAnimatable animatable = (GeoAnimatable) probe;
        GeckoModelScan.Result scan = captureModel(probe, renderer, 0);
        var paths = new HashMap<String, Integer>();
        for (BodyPlan.Box box : scan.plan().boxes()) paths.put(box.path(), box.index());
        return new Binding() {
            @Override
            public void renderLayers(CorpseRenderContext corpse, PoseStack stack, MultiBufferSource buffers,
                                     int light, float partialTick) {
                var type = geo.getRenderType(animatable, geo.getTextureLocation(animatable), buffers, partialTick);
                if (type == null || geo.getRenderLayers().isEmpty()) return;
                GeckoRendererState.withEntity(geo, probe, () -> {
                    try (var context = new GeckoLayerContext(corpse, stack, scan, partialTick)) {
                        stack.pushPose();
                        try {
                            geo.applyRenderLayers(stack, animatable, model(probe, renderer), type, buffers,
                                    buffers.getBuffer(type), partialTick, light, OverlayTexture.NO_OVERLAY);
                        } finally { stack.popPose(); }
                    }
                });
            }
            @Override
            public PartRenderer prepare(CorpseRenderContext corpse, MultiBufferSource buffers, float partialTick) {
                var texture = geo.getTextureLocation(animatable);
                var renderType = geo.getRenderType(animatable, texture, buffers, partialTick);
                return (box, stack, light) -> GeckoRendererState.withEntity(geo, probe, () -> {
                    Integer index = paths.get(box.path());
                    if (index == null || renderType == null) return;
                    var reference = scan.plan().boxes().get(index);
                    var part = scan.parts().get(index);
                    if (Boolean.TRUE.equals(part.bone().shouldNeverRender())) return;
                    var color = geo.getRenderColor(animatable, partialTick, light);
                    stack.scale(ratio(box.hx(), reference.hx()), ratio(box.hy(), reference.hy()),
                            ratio(box.hz(), reference.hz()));
                    stack.translate(reference.cx(), reference.cy()-1.501, -reference.cz());
                    stack.mulPoseMatrix(part.transform());
                    // 法线必须和顶点使用同一个完整变换，保留非均匀缩放和静态骨骼旋转
                    stack.last().normal().mul(new org.joml.Matrix3f(part.transform()).invert().transpose());
                    for (var cube : part.bone().getCubes()) {
                        if (cube.quads() == null) continue;
                        stack.pushPose();
                        try {
                            geo.renderCube(stack, cube, buffers.getBuffer(renderType), light, OverlayTexture.NO_OVERLAY,
                                    color.getRedFloat(), color.getGreenFloat(), color.getBlueFloat(), color.getAlphaFloat());
                        } finally { stack.popPose(); }
                    }
                    geo.applyRenderLayersForBone(stack, animatable, part.bone(), renderType, buffers,
                            buffers.getBuffer(renderType), partialTick, light, OverlayTexture.NO_OVERLAY);
                });
            }
        };
    }

    private static float ratio(double value, double base) {
        return base > 1e-6 ? (float) (value/base) : 1;
    }
}