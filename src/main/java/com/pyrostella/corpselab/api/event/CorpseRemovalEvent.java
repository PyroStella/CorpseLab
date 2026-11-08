package com.pyrostella.corpselab.api.event;

import net.minecraft.world.entity.Entity;

/** 尸体退出世界且交互清理完成后的通知；卸载不代表永久销毁 */
public final class CorpseRemovalEvent extends CorpseEvent {
    public enum Cause { EXPIRED, EMPTY, LIMIT, REVIVED, DISCARDED, UNLOADED }

    private final Cause cause;
    private final Entity.RemovalReason removalReason;

    public CorpseRemovalEvent(Entity corpse, Cause cause, Entity.RemovalReason removalReason) {
        super(corpse);
        this.cause = cause;
        this.removalReason = removalReason;
    }

    public Cause getCause() { return this.cause; }
    public Entity.RemovalReason getRemovalReason() { return this.removalReason; }
    public boolean isPermanent() { return this.cause != Cause.UNLOADED; }
}
