package com.pyrostella.corpselab.client.model.gecko;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.util.RenderUtils;

/** 原始方块绘制入口，避免 Mixin 改写接口 super 调用产生非法方法引用 */
public final class GeckoCubeRendering {
    private GeckoCubeRendering() {}

    public static void render(GeoRenderer<?> renderer, PoseStack stack, GeoCube cube, VertexConsumer buffer,
                              int light, int overlay, float red, float green, float blue, float alpha) {
        if (GeckoLayerContext.redirect(cube, buffer, light, overlay, red, green, blue, alpha)) return;
        if (cube.quads() == null) return;
        RenderUtils.translateToPivotPoint(stack, cube);
        RenderUtils.rotateMatrixAroundCube(stack, cube);
        RenderUtils.translateAwayFromPivotPoint(stack, cube);
        for (var quad : cube.quads()) {
            if (quad == null) continue;
            Vector3f normal = stack.last().normal().transform(new Vector3f(quad.normal()));
            RenderUtils.fixInvertedFlatCube(cube, normal);
            renderer.createVerticesOfQuad(quad, stack.last().pose(), normal, buffer,
                    light, overlay, red, green, blue, alpha);
        }
    }
}