package com.pyrostella.corpselab.internal.corpse;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.api.Corpses;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 直接通过 Forge 死亡事件创建尸体，物品转移由创建入口统一检查规则 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID)
public final class CorpseDeathEvents {
    private CorpseDeathEvents() { }

    /**
     * 死亡无法取消，可以直接生成尸体
     * */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity source = event.getEntity();
        if (!(source.level instanceof ServerLevel)) return;
        if (source instanceof ServerPlayer player) {
            Corpses.create(player, CorpseHandler.deathVelocity(player, event.getSource()));
        } else if (!(source instanceof Player) && source.getPose() != Pose.DYING) {
            Corpses.create(source);
        }
    }
}
