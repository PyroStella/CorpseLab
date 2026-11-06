package com.pyrostella.corpselab.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** 绘制已经定位到物理刚体的部件几何，不要求子节点表可修改 */
public final class ModelPartGeometry {
    private ModelPartGeometry() {}

    public static void renderCubes(PoseStack stack, VertexConsumer consumer, ModelPart part, int light) {
        for (ModelPart.Cube cube : part.cubes) {
            cube.compile(stack.last(), consumer, light, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
        }
    }
}