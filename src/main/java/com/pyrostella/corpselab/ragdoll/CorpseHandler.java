package com.pyrostella.corpselab.ragdoll;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.config.CorpseLabConfig;
import com.pyrostella.corpselab.entity.CorpseEntity;
import com.pyrostella.corpselab.entity.MobCorpseEntity;
import com.pyrostella.corpselab.entity.RagdollEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 把玩家的死亡变成一具保留其物品的尸体
 *
 * <p>刻意挂在死亡事件而非掉落事件上：在原版执行掉落逻辑之前
 * 清空背包，就没有东西可散落，物品会进入尸体而不会
 * 散在旁边的地上。保留物品栏选项仍然生效，身体照旧留下
 * 但玩家保住自己携带的东西
 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID)
public final class CorpseHandler {

    private CorpseHandler() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDeath(LivingDeathEvent event) {
        if (!CorpseLabConfig.SERVER.corpseEnabled.get()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide) {
            return;
        }
        if (player.isSpectator()) {
            return;
        }

        // 处于布娃娃状态的玩家死亡时已经躺着，先结束它，让尸体成为
        // 唯一留下的身体。此时玩家自身的 deltaMovement 无用，因为
        // 原版每个 tick 都会把乘客的速度清零，真正带着速度的是
        // 他们骑乘的那具身体
        Vec3 momentum;
        if (player.getVehicle() instanceof RagdollEntity ragdoll && ragdoll.skeleton() != null) {
            Vector3d velocity = ragdoll.skeleton().torso().linVel;
            momentum = new Vec3(velocity.x, velocity.y, velocity.z);
            ragdoll.getUp();
        } else {
            // 站着中弹意味着在原地倒下，真实的死亡正是这样
            // 剩下的交给重力
            momentum = player.getDeltaMovement().scale(20.0D);

            // 一击致命的爆炸没机会先把人掀倒，尸体只会
            // 原地倒下而不是被抛出去，因此补上爆炸触发器
            // 本应给出的初速度
            Vec3 blast = event.getSource().getSourcePosition();
            if (event.getSource().is(DamageTypeTags.IS_EXPLOSION) && blast != null) {
                Vec3 away = player.position()
                        .add(0.0D, player.getBbHeight() * 0.5D, 0.0D)
                        .subtract(blast);
                if (away.lengthSqr() > 1.0e-6D) {
                    momentum = away.normalize().scale(
                            CorpseLabConfig.SERVER.explosionLaunchMultiplier.get() * 2.0D);
                }
            }
        }
        CorpseEntity corpse = CorpseEntity.of(player, momentum);

        if (!player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            corpse.takeInventoryFrom(player);
        }

        player.level().addFreshEntity(corpse);
    }

    /**
     * 人形生物也会留下尸体，用来替代原版的倒地动画
     *
     * <p>哪些生物符合条件必须由配置列表决定：布娃娃由 Minecraft 人形模型的
     * 六个长方体构建，而生物使用哪种模型属于服务端
     * 看不到的客户端事实
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMobDeath(LivingDeathEvent event) {
        // 旧系统（Tumble 的 6 部件人形尸体）已由 plan.PlanCorpseHandler 取代
        // 两边都监听死亡会生成两份尸体，所以这里整段停用，代码保留备查
        //
        // if (!CorpseLabConfig.SERVER.mobCorpseEnabled.get()) {
        //     return;
        // }
        // if (event.getEntity() instanceof ServerPlayer || event.getEntity().level().isClientSide) {
        //     return;
        // }
        // if (!isRagdollable(event.getEntity().getType())) {
        //     return;
        // }
        //
        // Vec3 momentum = event.getEntity().getDeltaMovement().scale(20.0D);
        // event.getEntity().level().addFreshEntity(
        //         MobCorpseEntity.of(event.getEntity(), momentum));
    }

    private static boolean isRagdollable(EntityType<?> type) {
        String id = EntityType.getKey(type).toString();
        for (String entry : CorpseLabConfig.SERVER.mobCorpseTypes.get()) {
            if (entry.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 已静止的尸体不再发送姿态，后来到达的玩家会什么都看不到
     * 因此在他们开始跟踪这具身体时补发一次
     */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof RagdollEntity ragdoll
                && event.getEntity() instanceof ServerPlayer player) {
            ragdoll.sendPoseTo(player);
        }
    }

    /** 供其他代码判断某具尸体是否属于某人的便捷方法 */
    public static boolean isOwner(CorpseEntity corpse, Player player) {
        return player.getUUID().equals(corpse.getOwnerId());
    }
}
