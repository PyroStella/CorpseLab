package com.pyrostella.corpselab.ragdoll;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.config.CorpseLabConfig;
import com.pyrostella.corpselab.entity.CorpseEntity;
import com.pyrostella.corpselab.entity.SettlingBody;
import com.pyrostella.corpselab.plan.CorpseSourceData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
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

        // 继承死亡时的三轴物理速度，保留下落、移动和已施加的击退
        Vec3 momentum = CorpseSourceData.velocity(player);

        // 一击致命的爆炸会给尸体补上朝外的初速度
        Vec3 blast = event.getSource().getSourcePosition();
        if (event.getSource().is(DamageTypeTags.IS_EXPLOSION) && blast != null) {
            Vec3 away = player.position()
                    .add(0.0D, player.getBbHeight() * 0.5D, 0.0D)
                    .subtract(blast);
            if (away.lengthSqr() > 1.0e-6D) {
                momentum = momentum.add(away.normalize().scale(
                        CorpseLabConfig.SERVER.corpseExplosionLaunchMultiplier.get() * 2.0D));
            }
        }
        CorpseEntity corpse = CorpseEntity.of(player, momentum);

        if (!player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            corpse.takeInventoryFrom(player);
        }

        player.level().addFreshEntity(corpse);
    }

    /**
     * 已静止的尸体不再发送姿态，后来到达的玩家会什么都看不到
     * 因此在他们开始跟踪这具身体时补发一次
     */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof SettlingBody body
                && event.getEntity() instanceof ServerPlayer player) {
            body.sendPoseTo(player);
        }
    }

    /** 供其他代码判断某具尸体是否属于某人的便捷方法 */
    public static boolean isOwner(CorpseEntity corpse, Player player) {
        return player.getUUID().equals(corpse.getOwnerId());
    }
}
