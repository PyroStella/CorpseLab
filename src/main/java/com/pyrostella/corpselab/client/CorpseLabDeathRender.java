package com.pyrostella.corpselab.client;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.config.CorpseLabConfig;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
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

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player || entity.deathTime <= 0) {
            return;
        }
        if (!CorpseLabConfig.CLIENT.hideVanillaDeathAnimation.get()) {
            return;
        }
        if (!CorpseLabConfig.SERVER.mobCorpseEnabled.get()) {
            return;
        }

        String id = EntityType.getKey(entity.getType()).toString();
        for (String entry : CorpseLabConfig.SERVER.mobCorpseTypes.get()) {
            if (entry.equals(id)) {
                event.setCanceled(true);
                return;
            }
        }
    }
}
