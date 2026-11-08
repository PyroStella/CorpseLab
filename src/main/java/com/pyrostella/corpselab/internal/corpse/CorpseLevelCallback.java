package com.pyrostella.corpselab.internal.corpse;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import java.util.function.Consumer;

/** 保留世界管理器的移动和移除处理，仅在最终移除完成后清理尸体 */
record CorpseLevelCallback(Entity corpse, EntityInLevelCallback delegate,
                           Consumer<Entity.RemovalReason> cleanup) implements EntityInLevelCallback {
    @Override
    public void onMove() {
        this.delegate.onMove();
    }

    @Override
    public void onRemove(Entity.RemovalReason reason) {
        this.delegate.onRemove(reason);
        this.cleanup.accept(this.corpse.getRemovalReason());
    }
}
