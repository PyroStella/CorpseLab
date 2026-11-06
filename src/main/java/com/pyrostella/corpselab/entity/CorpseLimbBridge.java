package com.pyrostella.corpselab.entity;

import com.pyrostella.corpselab.CorpseLabRegistry;
import com.pyrostella.corpselab.physics.RigidBody;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/** 玩家与通用尸体共用的肢体实体生命周期和速度桥 */
final class CorpseLimbBridge {
    private final Entity owner;
    private final List<CorpseLimbEntity> limbs = new ArrayList<>();

    CorpseLimbBridge(Entity owner) {
        this.owner = owner;
    }

    boolean consume(List<RigidBody> bodies) {
        if (this.limbs.size() != bodies.size() || this.limbs.stream().anyMatch(Entity::isRemoved)) {
            this.clear();
            for (RigidBody body : bodies) {
                CorpseLimbEntity limb = CorpseLabRegistry.CORPSE_LIMB.get().create(this.owner.level());
                if (limb != null) {
                    limb.attach(this.owner, body);
                    if (this.owner.level().addFreshEntity(limb)) {
                        this.limbs.add(limb);
                    }
                }
            }
        }
        boolean changed = false;
        for (CorpseLimbEntity limb : this.limbs) {
            changed |= limb.consumeMotion();
        }
        return changed;
    }

    void publish() {
        for (CorpseLimbEntity limb : this.limbs) {
            limb.publishPose();
        }
    }

    void clear() {
        for (CorpseLimbEntity limb : this.limbs) {
            limb.discard();
        }
        this.limbs.clear();
    }
}
