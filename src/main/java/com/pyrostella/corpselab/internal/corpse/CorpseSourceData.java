package com.pyrostella.corpselab.internal.corpse;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** 原生物的完整持久化快照，和只用于显示的外观副本分开保存 */
public final class CorpseSourceData {
    private CorpseSourceData() {}

    public static CompoundTag capture(LivingEntity source) {
        CompoundTag data = source.saveWithoutId(new CompoundTag());
        // saveWithoutId 省略类型 ID；完整快照显式保存类型和原 UUID
        data.putString("id", EntityType.getKey(source.getType()).toString());
        data.putUUID("UUID", source.getUUID());
        return data.copy();
    }

    /** 原版速度为格每 tick，物理求解器使用格每秒 */
    public static Vec3 velocity(LivingEntity source) {
        return source.getDeltaMovement().scale(20.0D);
    }
}