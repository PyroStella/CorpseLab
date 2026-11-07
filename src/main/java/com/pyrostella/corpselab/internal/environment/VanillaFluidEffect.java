package com.pyrostella.corpselab.internal.environment;

import com.pyrostella.corpselab.api.environment.LimbEffects;
import com.pyrostella.corpselab.api.environment.LimbEnvironmentEffect;

/** 原有水流由 Entity 接收，此处保留原有的流体阻尼与物理参数 */
public final class VanillaFluidEffect implements LimbEnvironmentEffect {
    @Override
    public void tick(LimbEffects.Context context) {
        var limb = context.limb();
        if (limb.isInWaterOrBubble()) {
            limb.setDeltaMovement(limb.getDeltaMovement().scale(0.8D));
            context.setPhysics(1.0D / 16.0D, 0.1D, 1.0D);
        } else if (limb.isInLava()) {
            limb.setDeltaMovement(limb.getDeltaMovement().scale(0.5D));
        }
    }
}
