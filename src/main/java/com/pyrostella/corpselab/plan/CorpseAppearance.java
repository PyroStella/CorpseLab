package com.pyrostella.corpselab.plan;

import com.pyrostella.corpselab.CorpseLab;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** 统一保留源实体的可序列化状态，渲染探针不加入世界、不执行 AI 或掉落 */
public final class CorpseAppearance {
    private static final int MAX_BYTES = 1024 * 1024;

    private CorpseAppearance() {
    }

    public static CompoundTag capture(LivingEntity source) {
        CompoundTag tag = source.saveWithoutId(new CompoundTag());
        // 探针只代表源实体自身，不能带入骑乘实体或恢复拴绳关系
        tag.remove("Passengers");
        tag.remove("Leash");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeNbt(tag);
            if (buffer.readableBytes() > MAX_BYTES) {
                CorpseLab.LOGGER.warn("尸体外观数据过大，使用默认外观：{}", source.getType());
                return new CompoundTag();
            }
            return tag;
        } finally {
            buffer.release();
        }
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
