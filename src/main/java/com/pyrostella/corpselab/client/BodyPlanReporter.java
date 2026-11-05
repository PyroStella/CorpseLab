package com.pyrostella.corpselab.client;

import java.util.HashSet;
import java.util.Set;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.net.CorpseLabNetwork;
import com.pyrostella.corpselab.net.PlanSubmitPacket;
import com.pyrostella.corpselab.plan.BodyPlan;
import com.pyrostella.corpselab.plan.ModelPartScanner;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端第一次看到某类生物时扫描模型，并把物理骨架上报给服务端
 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID, value = Dist.CLIENT)
public final class BodyPlanReporter {

    /** 本次连接中已经尝试扫描过的类型 */
    private static final Set<ResourceLocation> SCANNED = new HashSet<>();

    /** 本次连接中已经成功上报的类型 */
    private static final Set<ResourceLocation> REPORTED = new HashSet<>();

    /** 已经成功上报，并且至少在活着时渲染过的类型 */
    private static final Set<ResourceLocation> REPORTED_ALIVE = new HashSet<>();

    private BodyPlanReporter() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    @SuppressWarnings("rawtypes")
    public static void onRenderLiving(RenderLivingEvent.Pre event) {
        LivingEntity entity = event.getEntity();
        ResourceLocation id = EntityType.getKey(entity.getType());

        if (!SCANNED.add(id)) {
            if (entity.deathTime <= 0 && REPORTED.contains(id)) {
                REPORTED_ALIVE.add(id);
            }
            return;
        }

        EntityRenderer renderer = event.getRenderer();
        if (!(renderer instanceof LivingEntityRenderer<?, ?> livingRenderer)) {
            return;
        }

        EntityModel<?> model = livingRenderer.getModel();
        BodyPlan plan = ModelPartScanner.scan(model);
        if (plan.boxCount() == 0) {
            return;
        }

        CorpseLabNetwork.CHANNEL.sendToServer(new PlanSubmitPacket(id, plan));
        REPORTED.add(id);
        if (entity.deathTime <= 0) {
            REPORTED_ALIVE.add(id);
        }
        CorpseLab.LOGGER.info("上报骨架 {}：{} 盒 / {} 关节",
                id, plan.boxCount(), plan.jointCount());
    }

    /** 这种生物在死亡前已经具备生成通用尸体的必要条件 */
    public static boolean expectsCorpse(EntityType<?> type) {
        return REPORTED_ALIVE.contains(EntityType.getKey(type));
    }

    /** 服务端骨架缓存不会跨连接保留，客户端记录也必须随连接重置 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SCANNED.clear();
        REPORTED.clear();
        REPORTED_ALIVE.clear();
        PlanModelCache.clear();
    }
}
