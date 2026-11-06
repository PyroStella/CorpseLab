package com.pyrostella.corpselab.environment;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;

/** 原有受伤击退策略，每个肢体单独保存爆心与冷却 */
final class DamageKnockbackEffect implements LimbEnvironmentEffect {
    private Vec3 explosionCentre;
    private long explosionTick = -1;
    private double explosionImpact;
    private long nextKnockbackTick;

    @Override
    public void hurt(LimbEffects.Context context, DamageSource source, float amount) {
        var limb = context.limb();
        Vec3 origin = source.getSourcePosition();
        double strength = 0.5D;
        if (source.is(DamageTypeTags.IS_EXPLOSION) && this.explosionTick == limb.level().getGameTime()) {
            origin = this.explosionCentre;
            strength *= Math.min(1.0D, this.explosionImpact * 2.0D);
        }
        if (origin != null && strength > 0 && limb.level().getGameTime() >= this.nextKnockbackTick) {
            Vec3 direction = limb.position().subtract(origin).multiply(1, 0, 1).normalize();
            Vec3 velocity = limb.getDeltaMovement();
            double lift = context.onGround() ? Math.min(0.5D, velocity.y / 2.0D + strength) : velocity.y;
            limb.setDeltaMovement(velocity.x / 2.0D + direction.x * strength,
                    lift, velocity.z / 2.0D + direction.z * strength);
            this.nextKnockbackTick = limb.level().getGameTime() + 10;
            limb.hasImpulse = true;
        }
    }

    @Override
    public void explosion(LimbEffects.Context context, Explosion explosion) {
        var limb = context.limb();
        double radius = explosion.radius * 2.0D;
        if (radius <= 0) {
            return;
        }
        Vec3 centre = explosion.getPosition();
        this.explosionCentre = centre;
        this.explosionTick = limb.level().getGameTime();
        this.explosionImpact = Math.max(0, 1.0D - limb.position().distanceTo(centre) / radius)
                * Explosion.getSeenPercent(centre, limb);
    }
}
