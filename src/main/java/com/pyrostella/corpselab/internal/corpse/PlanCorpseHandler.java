package com.pyrostella.corpselab.internal.corpse;

import com.pyrostella.corpselab.api.model.BodyPlan;
import com.pyrostella.corpselab.internal.skeleton.PlanRegistry;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.api.Corpses;
import com.pyrostella.corpselab.internal.network.CorpseLabNetwork;
import com.pyrostella.corpselab.internal.network.PlanPosePacket;
import com.pyrostella.corpselab.internal.network.PlanSyncPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

/**
 * 死亡钩子：生物死亡时，如果服务端有这个类型的骨架，就生成一具通用尸体
 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID)
public final class PlanCorpseHandler {

    private PlanCorpseHandler() {
    }

    public static void onDeath(LivingEntity entity) {
        if (entity.level().isClientSide() || entity instanceof Player) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        BodyPlan plan = PlanRegistry.get(entity);
        if (plan == null) {
            // 还没收到客户端上报，这一只就先不留尸体
            return;
        }

        var result = Corpses.create(entity, plan, CorpseSourceData.velocity(entity));
        if (result.isPresent()) {
            CorpseLab.LOGGER.debug("生成尸体 {}", EntityType.getKey(entity.getType()));
        }
    }

    /** 玩家开始跟踪尸体时，把骨架补发给他 */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof PlanCorpseEntity corpse
                && event.getEntity() instanceof ServerPlayer player) {
            CorpseLabNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new PlanSyncPacket(corpse.getId(), corpse.sourceType(), corpse.plan(), corpse.appearance(), corpse.sourceEntityId().orElse(null))
            );
            CorpseLabNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), PlanPosePacket.of(corpse));
        }
    }
}
