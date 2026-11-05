package com.pyrostella.corpselab.ragdoll;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.entity.RagdollEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 阻止已倒下的玩家在允许之前离开自己的布娃娃
 *
 * <p>只给模组自己的起身数据包加限制还不够。潜行是原版通用的
 * "离开载具"手势，它从不询问本模组，于是在爆炸中按住 Shift，或者在
 * 飞向空中的途中轻点一下，玩家都会立即下马并取消这次抛射。所有下马路径
 * 最终都走到 {@code removeVehicle}，本事件正是在那里触发，所以在这里
 * 拦截即可一次覆盖全部路径
 *
 * <p>这里真正危险的失败是相反的一种：一次本应放行的下马被取消
 * 会让玩家永远被焊死在一具身体上。因此任何不属于
 * 玩家主动提前起身的下马都会被放行
 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID)
public final class RagdollDismountGuard {

    private RagdollDismountGuard() {
    }

    @SubscribeEvent
    public static void onDismount(EntityMountEvent event) {
        if (!event.isDismounting()) {
            return;
        }
        if (!(event.getEntityBeingMounted() instanceof RagdollEntity ragdoll)) {
            return;
        }
        if (!(event.getEntityMounting() instanceof Player rider)) {
            return;
        }

        // 放行一切不属于玩家主动提前起身的情况：模组主动释放
        // 布娃娃被移除、骑手已死亡，或者骑手因为退出游戏
        // 或切换维度而被移除
        if (ragdoll.isReleasing() || ragdoll.isRemoved() || rider.isRemoved() || !rider.isAlive()) {
            return;
        }

        boolean allowed = ragdoll.level().isClientSide
                ? ragdoll.canGetUpSynced()
                : ragdoll.canGetUp();

        if (!allowed) {
            event.setCanceled(true);
        }
    }
}
