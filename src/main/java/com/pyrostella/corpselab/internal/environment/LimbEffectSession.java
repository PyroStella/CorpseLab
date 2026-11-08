package com.pyrostella.corpselab.internal.environment;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.api.CorpseView;
import com.pyrostella.corpselab.api.Corpses;
import com.pyrostella.corpselab.api.Registration;
import com.pyrostella.corpselab.api.environment.LimbEffects;
import com.pyrostella.corpselab.api.environment.LimbEnvironmentEffect;
import com.pyrostella.corpselab.internal.corpse.CorpseLimbEntity;
import com.pyrostella.corpselab.internal.registry.ExtensionRegistry;
import com.pyrostella.corpselab.physics.RigidBody;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import java.util.*;
import java.util.function.Supplier;

/** 每肢体调度器，仅注册快照变化时刷新实例和执行顺序 */
public final class LimbEffectSession {
    private static final ExtensionRegistry<Supplier<? extends LimbEnvironmentEffect>> REGISTRY = new ExtensionRegistry<>();

    static {
        register(new ResourceLocation(CorpseLab.MOD_ID, "vanilla_fluids"), 0, VanillaFluidEffect::new);
        register(new ResourceLocation(CorpseLab.MOD_ID, "damage_knockback"), 0, DamageKnockbackEffect::new);
    }

    public static Registration register(ResourceLocation id, int priority,
                                        Supplier<? extends LimbEnvironmentEffect> factory) {
        return REGISTRY.register(id.toString(), priority, factory);
    }

    private final EnvironmentContext context;
    private Object version;
    private Map<ExtensionRegistry.Entry<Supplier<? extends LimbEnvironmentEffect>>, LimbEnvironmentEffect> instances = new IdentityHashMap<>();
    private List<LimbEnvironmentEffect> ordered = List.of();

    public LimbEffectSession(CorpseLimbEntity limb, RigidBody body) {
        this.context = new EnvironmentContext(limb, body);
    }

    private List<LimbEnvironmentEffect> handlers() {
        var entries = REGISTRY.entries();
        if (this.version != entries) {
            Map<ExtensionRegistry.Entry<Supplier<? extends LimbEnvironmentEffect>>, LimbEnvironmentEffect> current = new IdentityHashMap<>();
            List<LimbEnvironmentEffect> handlers = new ArrayList<>(entries.size());
            for (int i = entries.size() - 1; i >= 0; i--) {
                var entry = entries.get(i);
                LimbEnvironmentEffect effect = this.instances.get(entry);
                if (effect == null) effect = Objects.requireNonNull(entry.value().get());
                current.put(entry, effect);
                handlers.add(effect);
            }
            this.instances = current;
            this.ordered = List.copyOf(handlers);
            this.version = entries;
        }
        return this.ordered;
    }

    public boolean tick() {
        this.context.setPhysics(1.0D, 1.0D, 0.85D);
        for (LimbEnvironmentEffect effect : this.handlers()) {
            if (effect.supports(this.context)) effect.tick(this.context);
        }
        return this.context.commitPhysics();
    }

    public void hurt(DamageSource source, float amount) {
        for (LimbEnvironmentEffect effect : this.handlers()) {
            if (effect.supports(this.context)) effect.hurt(this.context, source, amount);
        }
    }

    public void explosion(Explosion explosion) {
        for (LimbEnvironmentEffect effect : this.handlers()) {
            if (effect.supports(this.context)) effect.explosion(this.context, explosion);
        }
    }

    private static final class EnvironmentContext implements LimbEffects.Context {
        private final Entity limb;
        private final CorpseView corpse;
        private final RigidBody body;
        private double gravity, friction, damping;

        EnvironmentContext(CorpseLimbEntity limb, RigidBody body) {
            this.limb = limb;
            this.corpse = Corpses.find(limb.corpse()).orElseThrow();
            this.body = body;
        }

        @Override public Entity limb() { return this.limb; }
        @Override public CorpseView corpse() { return this.corpse; }
        @Override public boolean onGround() { return this.body.onGround; }

        @Override
        public void setPhysics(double gravityScale, double frictionScale, double supportDamping) {
            if (!Double.isFinite(gravityScale) || !Double.isFinite(frictionScale)
                    || !Double.isFinite(supportDamping) || frictionScale < 0
                    || supportDamping < 0 || supportDamping > 1) {
                throw new IllegalArgumentException("无效的肢体环境参数");
            }
            this.gravity = gravityScale;
            this.friction = frictionScale;
            this.damping = supportDamping;
        }

        boolean commitPhysics() {
            boolean changed = this.body.gravityScale != this.gravity
                    || this.body.frictionScale != this.friction || this.body.supportDamping != this.damping;
            this.body.gravityScale = this.gravity;
            this.body.frictionScale = this.friction;
            this.body.supportDamping = this.damping;
            return changed;
        }
    }
}
