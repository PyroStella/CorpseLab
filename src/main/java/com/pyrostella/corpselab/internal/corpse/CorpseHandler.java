package com.pyrostella.corpselab.internal.corpse;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.internal.config.CorpseLabConfig;
import com.pyrostella.corpselab.internal.corpse.CorpseEntity;
import com.pyrostella.corpselab.internal.corpse.SettlingBody;
import com.pyrostella.corpselab.internal.corpse.CorpseSourceData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 玩家尸体的初速度计算与跟踪同步 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID)
public final class CorpseHandler {

    private CorpseHandler() {
    }

    /** 保存死亡时的三轴速度及爆炸外推，供事件准备尸体时使用 */
    static Vec3 deathVelocity(ServerPlayer player, net.minecraft.world.damagesource.DamageSource damage) {
        // 继承死亡时的三轴物理速度，保留下落、移动和已施加的击退
        Vec3 momentum = CorpseSourceData.velocity(player);

        // 一击致命的爆炸会给尸体补上朝外的初速度
        Vec3 blast = damage.getSourcePosition();
        if (damage.isExplosion() && blast != null) {
            Vec3 away = player.position()
                    .add(0.0D, player.getBbHeight() * 0.5D, 0.0D)
                    .subtract(blast);
            if (away.lengthSqr() > 1.0e-6D) {
                momentum = momentum.add(away.normalize().scale(
                        CorpseLabConfig.SERVER.corpseExplosionLaunchMultiplier.get() * 2.0D));
            }
        }
        return momentum;
    }

    /**
     * 已静止的尸体不再发送姿态，后来到达的玩家会什么都看不到
     * 因此在他们开始跟踪这具身体时补发一次
     */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof SettlingBody body && event.getEntity() instanceof ServerPlayer player) {
            body.sendPoseTo(player);
        }
    }

    /** 供其他代码判断某具尸体是否属于某人的便捷方法 */
    public static boolean isOwner(CorpseEntity corpse, Player player) {
        return player.getUUID().equals(corpse.getOwnerId());
    }
}
