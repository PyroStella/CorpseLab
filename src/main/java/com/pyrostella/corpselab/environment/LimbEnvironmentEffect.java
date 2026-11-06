package com.pyrostella.corpselab.environment;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Explosion;

/** 每个肢体拥有独立处理器实例，可以安全保存击退冷却等状态 */
public interface LimbEnvironmentEffect {
    default boolean supports(LimbEffects.Context context) {
        return true;
    }

    default void tick(LimbEffects.Context context) {
    }

    default void hurt(LimbEffects.Context context, DamageSource source, float amount) {
    }

    default void explosion(LimbEffects.Context context, Explosion explosion) {
    }
}
