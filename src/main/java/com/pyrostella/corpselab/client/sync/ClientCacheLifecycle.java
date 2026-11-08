package com.pyrostella.corpselab.client.sync;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.client.CorpseLabClient;
import com.pyrostella.corpselab.client.model.PlanModelCache;
import com.pyrostella.corpselab.client.render.CorpseLabDeathRender;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 连接、世界和资源分别管理缓存寿命，避免旧世界探针或实体编号污染 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID, value = Dist.CLIENT)
public final class ClientCacheLifecycle {
    private ClientCacheLifecycle() {
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) clearLevel();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BodyPlanReporter.clearConnection();
        clearLevel();
    }

    private static void clearLevel() {
        BodyPlanReporter.clearModels();
        PlanModelCache.clear();
        CorpseLabDeathRender.clear();
        CorpseLabClient.Hooks.resetGrab();
    }

    @Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Resources {
        private Resources() {
        }

        @SubscribeEvent
        public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) manager -> {
                BodyPlanReporter.clearModels();
                PlanModelCache.clear();
            });
        }
    }
}
