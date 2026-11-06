package com.pyrostella.corpselab.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pyrostella.corpselab.client.model.gecko.GeckoCubeRendering;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.world.entity.Entity;
import com.pyrostella.corpselab.client.model.gecko.GeckoRendererState;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.GeoRenderer;

/** 在实体渲染器补充方块绘制入口，保留原始顶点生成扩展点 */
@Mixin(value = GeoEntityRenderer.class, remap = false)
public abstract class GeckoCubeLayerMixin implements GeoRenderer, GeckoRendererState {
    @Shadow(remap = false) protected Entity animatable;

    public Entity corpselab$getEntity() { return this.animatable; }
    public void corpselab$setEntity(Entity entity) { this.animatable = entity; }
    @Override
    public void renderCube(PoseStack stack, GeoCube cube, VertexConsumer buffer, int light,
                           int overlay, float red, float green, float blue, float alpha) {
        GeckoCubeRendering.render(this, stack, cube, buffer, light, overlay, red, green, blue, alpha);
    }
}