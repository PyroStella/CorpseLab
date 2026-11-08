package com.pyrostella.corpselab.api.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pyrostella.corpselab.api.Registration;
import com.pyrostella.corpselab.client.model.CorpseLayerRenderer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/** 按渲染层注册材质与几何适配，无需依赖内部扫描结果或尸体实体 */
public final class CorpseLayers {
    private CorpseLayers() { }

    public interface Adapter {
        boolean supports(RenderLayer<?, ?> layer);
        void render(Context context);
    }

    public static Registration register(ResourceLocation id, int priority, Adapter adapter) {
        return CorpseLayerRenderer.register(id, priority, adapter);
    }

    public record Context(RenderLayer<?, ?> layer, EntityModel<?> model, LivingEntity probe,
                          CorpseRenderContext corpse, PoseStack stack, MultiBufferSource buffers,
                          int light, float partialTick) {
        /** 使用默认部件绘制，也可补充额外模型；特殊几何可直接使用公开绘制上下文 */
        public void renderModelParts(EntityModel<?>... extraModels) {
            CorpseLayerRenderer.renderModelParts(this, extraModels);
        }
    }
}
