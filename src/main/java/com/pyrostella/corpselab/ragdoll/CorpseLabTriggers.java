package com.pyrostella.corpselab.ragdoll;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.config.CorpseLabConfig;
import com.pyrostella.corpselab.entity.CorpseEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityStruckByLightningEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 所有不需要玩家主动请求就能把人掀倒的情形
 *
 * <p>这些情形全部经由 {@link RagdollController#tumbleFromTrigger}
 * 因此冷却、资格规则与抑制列表只在一处执行，不必在每个
 * 触发器里重复一遍
 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID)
public final class CorpseLabTriggers {

    /** 每个玩家上一次上报的运动，用于识别突然的停止 */
    private static final java.util.Map<java.util.UUID, Vec3> LAST_MOTION = new java.util.HashMap<>();

    private CorpseLabTriggers() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.player instanceof ServerPlayer player) {
            Suppressions.tick(player);
        }
    }

    /**
     * 由伤害驱动的触发：重着地、鞘翅撞墙或重击
     *
     * <p>挂在伤害事件而不是受伤事件上，取到的是护甲后的
     * 数值，阈值正是按这个口径设定的：下界合金救下的伤害
     * 也应该让你免于被掀翻
     */
    @SubscribeEvent
    public static void onDamage(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        DamageSource source = event.getSource();
        float amount = event.getAmount();
        CorpseLabConfig.Server config = CorpseLabConfig.SERVER;

        if (source.is(DamageTypes.FALL)) {
            // 为零表示只以高度为条件，此时伤害不应触发
            double minDamage = config.fallMinDamage.get();
            if (config.fallEnabled.get() && minDamage > 0.0D && amount >= minDamage) {
                // 用一部分落地速度把身体砸向地面
                double speed = Math.abs(player.getDeltaMovement().y) * 20.0D;
                RagdollController.tumbleFromTrigger(player,
                        new Vec3(0.0D, -speed * config.fallSlamMultiplier.get(), 0.0D));
            }
            return;
        }

        if (source.is(DamageTypes.FLY_INTO_WALL)) {
            if (config.crashEnabled.get() && amount >= config.crashMinDamage.get()) {
                RagdollController.tumbleFromTrigger(player,
                        player.getDeltaMovement().scale(20.0D * config.crashLaunchMultiplier.get()));
            }
            return;
        }

        // 爆炸在起爆时处理，那时爆炸的几何关系是已知的
        if (source.is(DamageTypes.EXPLOSION) || source.is(DamageTypes.PLAYER_EXPLOSION)) {
            return;
        }

        if (config.hitEnabled.get() && amount >= config.hitMinDamage.get()) {
            // 按打击本身的力度缩放，擦伤只是踉跄，暴击会把人抛出去
            //
            // 弹射物的缩放系数低得多。子弹伤害很高
            // 携带的动量却几乎为零，把击退绑在伤害上，会让
            // 本该原地倒下的人被一枪打飞
            double multiplier = isProjectile(source)
                    ? config.hitProjectileLaunchMultiplier.get()
                    : config.hitLaunchMultiplier.get();

            // 做了上限截断，因为只按伤害缩放时，一把打出三十点伤害的模组武器
            // 会把玩家以每秒四十五格的速度抛出。在用的枪械模组中有两个
            // 完全没有定义数据驱动的伤害类型，任何 id 列表都不可能
            // 完整，真正保证数值不失控的是这个上限
            double speed = Math.min(multiplier * amount, config.hitMaxLaunchSpeed.get());

            RagdollController.tumbleFromTrigger(player, awayFrom(source, player).scale(speed));
        }
    }

    /**
     * 撞击检测，由客户端上报的运动采样驱动
     *
     * <p>撞击就是速度的突然损失，所以需要两次采样：停止前的那个 tick 和
     * 停止的那个 tick。随后身体沿它原本前进的方向被抛出
     * 于是会撞进所碰到的东西里，而不是被弹回来
     */
    public static void onMotionSample(ServerPlayer player, Vec3 motion) {
        CorpseLabConfig.Server config = CorpseLabConfig.SERVER;

        Vec3 previous = LAST_MOTION.put(player.getUUID(), motion);
        if (previous == null || !config.impactEnabled.get()) {
            return;
        }

        double delta = previous.subtract(motion).length() * 20.0D;
        if (delta < config.impactMinVelocityDelta.get()) {
            return;
        }

        double launch = Math.min(delta, config.impactMaxVelocityDelta.get());
        if (previous.lengthSqr() < 1.0e-8D) {
            return;
        }
        RagdollController.tumbleFromTrigger(player, previous.normalize().scale(launch));
    }

    public static void forget(java.util.UUID playerId) {
        LAST_MOTION.remove(playerId);
    }

    /**
     * 爆炸掀翻，由几何关系而不是伤害驱动
     *
     * <p>读取爆炸伤害行不通。创造模式玩家完全不受伤害，所以在创造模式里
     * 测试时它从不触发；TNT 的伤害衰减又极快，只有站在炸药
     * 里面的玩家才能超过任何一个合理的阈值
     * 因此作用范围与力度改为来自爆炸自身的半径，这样附近的爆炸
     * 就能把人抛出，而不必被爆炸吞没
     *
     * <p>原版自身对两倍半径内的实体生效，因此这里采用该距离
     * 再加上一段可配置的余量
     */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        CorpseLabConfig.Server config = CorpseLabConfig.SERVER;
        if (!config.explosionEnabled.get()) {
            return;
        }

        double power = event.getExplosion().radius;
        if (power < config.explosionMinPower.get()) {
            return;
        }

        Vec3 centre = event.getExplosion().getPosition();
        double reach = power * 2.0D + config.explosionRadiusPadding.get();

        for (Entity entity : event.getAffectedEntities()) {
            // 躺在爆炸里的尸体也应该被炸飞，和活着的身体一样
            if (entity instanceof CorpseEntity corpse) {
                Vec3 offset = corpse.position().subtract(centre);
                double distance = offset.length();
                if (distance <= reach && distance > 1.0e-3D) {
                    double falloff = 1.0D - (distance / reach);
                    corpse.launchAndWake(offset.normalize()
                            .scale(config.explosionLaunchMultiplier.get() * power * falloff));
                }
                continue;
            }

            if (!(entity instanceof ServerPlayer player)) {
                continue;
            }

            // 从胸部位置开始测量，这样脚边的爆炸仍会把人向上
            // 并向外抛出，而不是纯粹横向推开
            Vec3 offset = player.position()
                    .add(0.0D, player.getBbHeight() * 0.5D, 0.0D)
                    .subtract(centre);

            double distance = offset.length();
            if (distance > reach) {
                continue;
            }
            if (distance < 1.0e-3D) {
                offset = new Vec3(0.0D, 1.0D, 0.0D);
                distance = 1.0e-3D;
            }

            double falloff = 1.0D - (distance / reach);
            double speed = config.explosionLaunchMultiplier.get() * power * falloff;

            RagdollController.tumbleFromTrigger(player, offset.normalize().scale(speed));
        }
    }

    /**
     * 重着地的另一半：不管为此付出了多少代价
     * 只去看他下落了多高
     *
     * <p>单看伤害无法很好地代表重着地。护甲、摔落缓冲和抗性都会削减它
     * 玩家可以掉三十格只受四点伤害。这里直接以下落距离为准
     * 两个条件彼此独立：满足任一个即可，把任一个的阈值设为零
     * 就能关掉它
     *
     * <p>落入水中根本不会走到这里：原版在进入流体时清空坠落距离
     * 所以该事件不会触发
     */
    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        CorpseLabConfig.Server config = CorpseLabConfig.SERVER;
        if (!config.fallEnabled.get()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        double minHeight = config.fallMinHeight.get();
        if (minHeight <= 0.0D || event.getDistance() < minHeight) {
            return;
        }

        double speed = Math.abs(player.getDeltaMovement().y) * 20.0D;
        RagdollController.tumbleFromTrigger(player,
                new Vec3(0.0D, -speed * config.fallSlamMultiplier.get(), 0.0D));
    }

    @SubscribeEvent
    public static void onLightning(EntityStruckByLightningEvent event) {
        CorpseLabConfig.Server config = CorpseLabConfig.SERVER;
        if (!config.lightningEnabled.get()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        RagdollController.tumbleFromTrigger(player,
                new Vec3(0.0D, config.lightningLaunchSpeed.get(), 0.0D));
    }

    /**
     * 一次打击应当按子弹或箭处理，而不是按推搡处理
     *
     * <p>仅靠原版标签不够：枪械模组会注册自己的伤害类型
     * 通常也不加入 minecraft:is_projectile——TACZ 声明 tacz:bullets
     * Superb Warfare 声明 superbwarfare:gun_damage，两者都不在原版标签里
     * 因此额外的 id 可以配置，每个 id 会先按伤害类型
     * 再按标签各匹配一次
     */
    private static boolean isProjectile(DamageSource source) {
        if (source.is(DamageTypeTags.IS_PROJECTILE)) {
            return true;
        }

        for (String entry : CorpseLabConfig.SERVER.projectileDamageTypes.get()) {
            ResourceLocation id = ResourceLocation.tryParse(entry);
            if (id == null) {
                continue;
            }
            boolean matchesType = source.typeHolder().unwrapKey()
                    .map(key -> key.location().equals(id))
                    .orElse(false);
            if (matchesType || source.is(TagKey.create(Registries.DAMAGE_TYPE, id))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 被 {@code source} 击中时抛出的方向。找不到来源时退化为竖直向上
     * 这样来路不明的一击仍然表现为被掀倒，而不是毫无反应
     */
    private static Vec3 awayFrom(DamageSource source, ServerPlayer player) {
        Vec3 origin = source.getSourcePosition();
        if (origin == null) {
            return new Vec3(0.0D, 1.0D, 0.0D);
        }
        Vec3 away = player.position().subtract(origin);
        if (away.lengthSqr() < 1.0e-6D) {
            return new Vec3(0.0D, 1.0D, 0.0D);
        }
        // 向上偏一点，否则一次打击只会让身体在地面上滑行
        return away.normalize().add(0.0D, 0.5D, 0.0D).normalize();
    }
}
