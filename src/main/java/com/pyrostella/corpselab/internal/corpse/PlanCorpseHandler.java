package com.pyrostella.corpselab.internal.corpse;


import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.internal.network.CorpseLabNetwork;
import com.pyrostella.corpselab.internal.network.PlanPosePacket;
import com.pyrostella.corpselab.internal.network.PlanSyncPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

/**
 * 新跟踪通用尸体时补发骨架、外观和姿态
 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID)
public final class PlanCorpseHandler {

    private PlanCorpseHandler() {
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
