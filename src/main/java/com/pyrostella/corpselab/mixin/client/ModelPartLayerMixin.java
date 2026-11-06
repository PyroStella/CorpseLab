package com.pyrostella.corpselab.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pyrostella.corpselab.client.model.LayerPartContext;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 同时列出开发名称与 Forge 运行时名称，不依赖额外映射文件 */
@Mixin(ModelPart.class)
public abstract class ModelPartLayerMixin {
    @Inject(method = {"compile", "m_104290_"}, at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void corpselab$renderLayerPart(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay,
                                          float red, float green, float blue, float alpha, CallbackInfo callback) {
        if (LayerPartContext.redirect((ModelPart) (Object) this, consumer, light, overlay, red, green, blue, alpha)) {
            callback.cancel();
        }
    }
}
