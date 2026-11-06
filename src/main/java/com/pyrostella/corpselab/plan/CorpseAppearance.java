package com.pyrostella.corpselab.plan;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** 统一保留源实体的可序列化状态，渲染探针不加入世界、不执行 AI 或掉落 */
public final class CorpseAppearance {

    private CorpseAppearance() {
    }

    public static CompoundTag capture(LivingEntity source) {
        return fromSnapshot(CorpseSourceData.capture(source));
    }

    public static CompoundTag fromSnapshot(CompoundTag snapshot) {
        CompoundTag tag = snapshot.copy();
        // 探针只代表源实体自身，不能带入骑乘实体或恢复拴绳关系
        tag.remove("Passengers");
        tag.remove("Leash");
        return tag;
    }

    public static void apply(LivingEntity probe, CompoundTag appearance) {
        if (!appearance.isEmpty()) {
            probe.load(appearance.copy());
            probe.setHealth(Math.max(0.001F, probe.getHealth()));
            probe.deathTime = 0;
            probe.hurtTime = 0;
        }
        probe.setPos(0, -1000, 0);
        probe.setDeltaMovement(Vec3.ZERO);
        probe.setNoGravity(true);
    }
}
