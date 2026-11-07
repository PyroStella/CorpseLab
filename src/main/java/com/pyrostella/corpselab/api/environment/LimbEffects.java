package com.pyrostella.corpselab.api.environment;

import com.pyrostella.corpselab.internal.environment.DamageKnockbackEffect;
import com.pyrostella.corpselab.internal.environment.VanillaFluidEffect;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.internal.corpse.CorpseLimbEntity;
import com.pyrostella.corpselab.physics.RigidBody;
import com.pyrostella.corpselab.internal.registry.ExtensionRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Explosion;

import java.util.*;
import java.util.function.Supplier;

/** 环境扩展入口：低优先级先执行，高优先级可覆盖环境参数或补充施力 */
public final class LimbEffects {
    private static final ExtensionRegistry<Supplier<? extends LimbEnvironmentEffect>> REGISTRY = new ExtensionRegistry<>();

    static {
        register(new ResourceLocation(CorpseLab.MOD_ID, "vanilla_fluids"), 0, VanillaFluidEffect::new);
        register(new ResourceLocation(CorpseLab.MOD_ID, "damage_knockback"), 0, DamageKnockbackEffect::new);
    }

    private LimbEffects() {
    }

    /** 通常在模组初始化时注册；返回句柄可撤销，既有肢体也会接收注册变化 */
    public static ExtensionRegistry.Registration register(ResourceLocation id, int priority, Supplier<? extends LimbEnvironmentEffect> factory) {
        return REGISTRY.register(id.toString(), priority, factory);
    }

    public static Session create(CorpseLimbEntity limb, RigidBody body) {
        return new Session(new Context(limb, body));
    }

    public static final class Context {
        private final CorpseLimbEntity limb;
        private final RigidBody body;
        private double gravity = 1.0D;
        private double friction = 1.0D;
        private double damping = 0.85D;

        private Context(CorpseLimbEntity limb, RigidBody body) {
            this.limb = limb;
            this.body = body;
        }

        public CorpseLimbEntity limb() {
            return this.limb;
        }

        public boolean onGround() {
            return this.body.onGround;
        }

        /** 仅在 tick 回调中设置，所有处理器完成后统一写入，避免参数重置引发虚假唤醒 */
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

        private boolean commitPhysics() {
            boolean changed = this.body.gravityScale != this.gravity
                    || this.body.frictionScale != this.friction || this.body.supportDamping != this.damping;
            this.body.gravityScale = this.gravity;
            this.body.frictionScale = this.friction;
            this.body.supportDamping = this.damping;
            return changed;
        }
    }

    public static final class Session {
        private final Context context;
        private final Map<ExtensionRegistry.Entry<Supplier<? extends LimbEnvironmentEffect>>, LimbEnvironmentEffect> instances = new HashMap<>();

        private Session(Context context) {
            this.context = context;
        }

        private List<ExtensionRegistry.Entry<Supplier<? extends LimbEnvironmentEffect>>> handlers() {
            var entries = REGISTRY.entries();
            this.instances.keySet().retainAll(entries);
            for (var entry : entries) {
                this.instances.computeIfAbsent(entry, key -> Objects.requireNonNull(key.value().get()));
            }
            return entries;
        }

        public boolean tick() {
            this.context.setPhysics(1.0D, 1.0D, 0.85D);
            var entries = this.handlers();
            for (int i = entries.size() - 1; i >= 0; i--) {
                LimbEnvironmentEffect effect = this.instances.get(entries.get(i));
                if (effect.supports(this.context)) {
                    effect.tick(this.context);
                }
            }
            return this.context.commitPhysics();
        }

        public void hurt(DamageSource source, float amount) {
            var entries = this.handlers();
            for (int i = entries.size() - 1; i >= 0; i--) {
                LimbEnvironmentEffect effect = this.instances.get(entries.get(i));
                if (effect.supports(this.context)) {
                    effect.hurt(this.context, source, amount);
                }
            }
        }

        public void explosion(Explosion explosion) {
            var entries = this.handlers();
            for (int i = entries.size() - 1; i >= 0; i--) {
                LimbEnvironmentEffect effect = this.instances.get(entries.get(i));
                if (effect.supports(this.context)) {
                    effect.explosion(this.context, explosion);
                }
            }
        }
    }
}
