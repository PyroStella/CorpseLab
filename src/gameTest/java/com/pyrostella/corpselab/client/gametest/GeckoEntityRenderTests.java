package com.pyrostella.corpselab.client.gametest;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pyrostella.corpselab.CorpseLab;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** 真实调用 GeckoLib 内置测试生物的完整渲染器，覆盖首次绘制和尸体路径 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID, value = Dist.CLIENT)
public final class GeckoEntityRenderTests {
    private static boolean ran;

    @SubscribeEvent
    public static void onFrame(RenderLevelStageEvent event) {
        if (ran || !Boolean.getBoolean("corpselab.renderTest") || !ModList.get().isLoaded("geckolib")
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        ran = true;
        Minecraft minecraft = Minecraft.getInstance();
        int tested = 0;
        for (var entry : ForgeRegistries.ENTITY_TYPES.getEntries()) {
            var id = entry.getKey().location();
            if (!id.getNamespace().equals("geckolib")) continue;
            var created = entry.getValue().create(minecraft.level);
            if (!(created instanceof LivingEntity source)) continue;
            try {
                source.setPos(minecraft.player.position());
                var renderer = minecraft.getEntityRenderDispatcher().getRenderer(source);
                var alive = new CorpseLayerRenderTests.RecordingBuffers();
                renderer.render(source, 0, 0, new PoseStack(), alive, 0xF000F0);
                renderer.render(source, 0, 0.5f, new PoseStack(), alive, 0xF000F0);
                if (alive.vertexCount() == 0) throw new IllegalStateException("活体没有输出顶点");
                var corpse = CorpseLayerRenderTests.render(source);
                if (corpse.vertexCount() == 0) throw new IllegalStateException("尸体没有输出顶点");
                tested++;
                CorpseLab.LOGGER.info("GECKO_ENTITY_RENDER_OK {} alive={} corpse={}", id, alive.vertexCount(), corpse.vertexCount());
            } catch (Throwable failure) {
                CorpseLab.LOGGER.error("GECKO_ENTITY_RENDER_FAILED {}", id, failure);
                throw new IllegalStateException("GeckoLib 实体渲染回归失败: " + id, failure);
            } finally {
                created.discard();
            }
        }
        if (tested == 0) throw new IllegalStateException("未加载 GeckoLib 内置测试生物");
        CorpseLab.LOGGER.info("GECKO_ENTITY_RENDER_ALL_OK count={}", tested);
    }
}