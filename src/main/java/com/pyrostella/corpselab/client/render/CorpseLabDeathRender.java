package com.pyrostella.corpselab.client.render;

import com.pyrostella.corpselab.client.sync.BodyPlanReporter;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.internal.config.CorpseLabConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 对任何会留下尸体的生物隐藏原版的死亡动画
 *
 * <p>否则垂死的生物会不断倒在自己的尸体上，同一处出现两具身体
 * 其中一具还在穿过另一具
 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID, value = Dist.CLIENT)
public final class CorpseLabDeathRender {

    private CorpseLabDeathRender() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player || entity.deathTime <= 0) {
            return;
        }
        if (!CorpseLabConfig.CLIENT.hideVanillaDeathAnimation.get()) {
            return;
        }
        if (BodyPlanReporter.expectsCorpse(entity.getType())) {
            event.setCanceled(true);
        }
    }
}
