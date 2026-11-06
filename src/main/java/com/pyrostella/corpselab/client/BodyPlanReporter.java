package com.pyrostella.corpselab.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.net.CorpseLabNetwork;
import com.pyrostella.corpselab.net.PlanInstancePacket;
import com.pyrostella.corpselab.net.PlanSubmitPacket;
import com.pyrostella.corpselab.plan.BodyPlan;
import com.pyrostella.corpselab.client.model.CorpseModelAdapter;
import com.pyrostella.corpselab.client.model.CorpseModelAdapters;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;

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

    /** 类型级原始模型扫描，不包含具体实体的缩放 */
    private static final Map<ResourceLocation, ScanCache> SCANS = new HashMap<>();

    /** 每个实体最近一次上报的实际渲染尺寸 */
    private static final Map<Integer, InstanceReport> INSTANCES = new HashMap<>();

    private static final int INSTANCE_REPORT_INTERVAL = 5;
    private static final double PLAN_EPSILON = 1.0D / 128.0D;

    private BodyPlanReporter() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    @SuppressWarnings("rawtypes")
    public static void onRenderLiving(RenderLivingEvent.Pre event) {
        LivingEntity entity = event.getEntity();
        CorpseModelAdapter adapter = CorpseModelAdapters.select(entity, event.getRenderer());
        if (adapter != null && adapter.usesLivingRenderEvent()) {
            report(entity, event.getRenderer(), adapter, event.getPartialTick());
        }
    }

    /** 没有 RenderLivingEvent 的模型体系也能仅通过注册适配器完成发现与上报 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.phase != TickEvent.Phase.END || minecraft.level == null || minecraft.player == null
                || minecraft.level.getGameTime() % INSTANCE_REPORT_INTERVAL != 0
                || !CorpseModelAdapters.needsDiscovery()) {
            return;
        }
        for (Entity candidate : minecraft.level.entitiesForRendering()) {
            if (candidate instanceof LivingEntity entity && entity.distanceToSqr(minecraft.player) <= 96.0D * 96.0D) {
                EntityRenderer<?> renderer = minecraft.getEntityRenderDispatcher().getRenderer(entity);
                CorpseModelAdapter adapter = CorpseModelAdapters.select(entity, renderer);
                if (adapter != null && !adapter.usesLivingRenderEvent()) {
                    report(entity, renderer, adapter, 1.0F);
                }
            }
        }
    }

    /** 专用渲染事件可在准确的采样时机主动上报，无需修改尸体核心代码 */
    public static void report(LivingEntity entity, EntityRenderer<?> renderer, float partialTick) {
        CorpseModelAdapter adapter = CorpseModelAdapters.select(entity, renderer);
        if (adapter != null) {
            report(entity, renderer, adapter, partialTick);
        }
    }

    private static void report(LivingEntity entity, EntityRenderer<?> renderer,
                               CorpseModelAdapter adapter, float partialTick) {
        ResourceLocation id = EntityType.getKey(entity.getType());
        Object model = adapter.modelKey(entity, renderer);
        ScanCache cached = SCANS.get(id);
        if (cached == null || cached.model() != model || cached.adapter() != adapter) {
            CorpseModelAdapter.Scan scan = adapter.scan(entity, renderer);
            if (scan.plan().boxCount() == 0) {
                return;
            }
            cached = new ScanCache(model, adapter, scan);
            SCANS.put(id, cached);
        }

        if (SCANNED.add(id)) {
            CorpseLabNetwork.CHANNEL.sendToServer(new PlanSubmitPacket(id, cached.scan().plan()));
            REPORTED.add(id);
            CorpseLab.LOGGER.info("上报骨架 {}：{} 盒 / {} 关节",
                    id, cached.scan().plan().boxCount(), cached.scan().plan().jointCount());
        }

        if (entity.deathTime <= 0 && REPORTED.contains(id)) {
            REPORTED_ALIVE.add(id);
        }

        InstanceReport previous = INSTANCES.get(entity.getId());
        if (previous != null && previous.type().equals(id)
                && entity.tickCount - previous.tick() < INSTANCE_REPORT_INTERVAL) {
            return;
        }

        BodyPlan instancePlan = cached.scan().capture(entity, renderer, partialTick);
        if (previous == null || !previous.type().equals(id)
                || differs(previous.plan(), instancePlan)) {
            CorpseLabNetwork.CHANNEL.sendToServer(
                    new PlanInstancePacket(entity.getId(), instancePlan));
        }
        // 即使尺寸没变也推进采样时钟，避免稳定实体退化成逐帧比较
        INSTANCES.put(entity.getId(), new InstanceReport(id, entity.tickCount, instancePlan));
    }

    private static boolean differs(BodyPlan first, BodyPlan second) {
        if (first.boxCount() != second.boxCount()) {
            return true;
        }
        for (int i = 0; i < first.boxCount(); i++) {
            BodyPlan.Box a = first.boxes().get(i);
            BodyPlan.Box b = second.boxes().get(i);
            if (different(a.cx(), b.cx()) || different(a.cy(), b.cy()) || different(a.cz(), b.cz())
                    || different(a.hx(), b.hx()) || different(a.hy(), b.hy()) || different(a.hz(), b.hz())
                    || different(a.px(), b.px()) || different(a.py(), b.py()) || different(a.pz(), b.pz())) {
                return true;
            }
        }
        return false;
    }

    private static boolean different(double first, double second) {
        return Math.abs(first - second) > PLAN_EPSILON;
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
        SCANS.clear();
        INSTANCES.clear();
        PlanModelCache.clear();
    }

    private record ScanCache(Object model, CorpseModelAdapter adapter, CorpseModelAdapter.Scan scan) {
    }

    private record InstanceReport(ResourceLocation type, int tick, BodyPlan plan) {
    }
}
