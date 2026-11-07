package com.pyrostella.corpselab.client.render;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.client.model.PlanModelCache;
import com.pyrostella.corpselab.internal.config.CorpseLabConfig;
import com.pyrostella.corpselab.internal.corpse.PlanCorpseEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 只有服务端确认且本客户端已能绘制尸体时才隐藏来源死亡模型 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID, value = Dist.CLIENT)
public final class CorpseLabDeathRender {
    private static final Map<UUID, WeakReference<PlanCorpseEntity>> CONFIRMED = new HashMap<>();

    private CorpseLabDeathRender() {
    }

    public static void confirm(@Nullable UUID sourceEntityId, PlanCorpseEntity corpse) {
        // 顺带清理已离开的尸体，记录不持有世界或实体的强引用
        CONFIRMED.values().removeIf(reference -> {
            PlanCorpseEntity existing = reference.get();
            return existing == null || existing.isRemoved();
        });
        if (sourceEntityId != null) CONFIRMED.put(sourceEntityId, new WeakReference<>(corpse));
    }

    public static boolean hasRenderableCorpse(LivingEntity source) {
        WeakReference<PlanCorpseEntity> reference = CONFIRMED.get(source.getUUID());
        PlanCorpseEntity corpse = reference == null ? null : reference.get();
        if (corpse == null || corpse.isRemoved() || corpse.level() != source.level()
                || source.level().getEntity(corpse.getId()) != corpse) {
            CONFIRMED.remove(source.getUUID());
            return false;
        }
        if (corpse.poses() == null || !corpse.poses().hasPose() || corpse.plan().boxCount() == 0
                || corpse.poses().partCount() != corpse.plan().boxCount()) {
            return false;
        }
        var type = ForgeRegistries.ENTITY_TYPES.getValue(corpse.sourceType());
        return type != null && PlanModelCache.get(corpse, type) != null;
    }

    public static void clear() {
        CONFIRMED.clear();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity source = event.getEntity();
        if (!(source instanceof Player) && source.deathTime > 0
                && CorpseLabConfig.CLIENT.hideVanillaDeathAnimation.get() && hasRenderableCorpse(source)) {
            event.setCanceled(true);
        }
    }
}
