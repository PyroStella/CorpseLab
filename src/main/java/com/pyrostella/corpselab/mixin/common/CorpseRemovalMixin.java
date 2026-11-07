package com.pyrostella.corpselab.mixin.common;

import com.pyrostella.corpselab.internal.corpse.CorpseLifecycle;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 捕获所有最终移除路径，包括隐藏区块卸载和直接跨维度移除。 */
@Mixin(Entity.class)
public abstract class CorpseRemovalMixin {
    @Inject(method = {"setRemoved", "m_142467_"}, at = @At("TAIL"), remap = false, require = 1, allow = 1)
    private void corpselab$finishRemoval(Entity.RemovalReason requestedReason, CallbackInfo callback) {
        Entity entity = (Entity) (Object) this;
        CorpseLifecycle.onEntityRemoved(entity, entity.getRemovalReason());
    }
}
