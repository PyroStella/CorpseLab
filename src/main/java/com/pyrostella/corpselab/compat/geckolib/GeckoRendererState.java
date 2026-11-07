package com.pyrostella.corpselab.compat.geckolib;

import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.renderer.GeoRenderer;

/** 尸体借用共享渲染器期间绑定外观探针，结束或异常后恢复原实体 */
public interface GeckoRendererState {
    Entity corpselab$getEntity();
    void corpselab$setEntity(Entity entity);

    static void withEntity(GeoRenderer<?> renderer, Entity entity, Runnable action) {
        GeckoRendererState state = (GeckoRendererState) renderer;
        Entity previous = state.corpselab$getEntity();
        state.corpselab$setEntity(entity);
        try { action.run(); } finally { state.corpselab$setEntity(previous); }
    }
}