package com.pyrostella.corpselab.internal.skeleton;

import com.pyrostella.corpselab.api.model.BodyPlan;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.pyrostella.corpselab.CorpseLab;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

/**
 * 服务端缓存：生物类型 → 骨架
 *
 * <p>数据由客户端上报（{@code PlanSubmitPacket}）。同类型只保留第一次的结果
 * 防止不同客户端反复覆盖导致姿态漂移。</p>
 *
 * <p>尺寸来自客户端模型，接收时校验数值和拓扑完整性，不使用固定部件数量或尺寸倍率上限。</p>
 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID)
public final class PlanRegistry {

    private static final Map<ResourceLocation, BodyPlan> PLANS = new ConcurrentHashMap<>();
    private static final Map<LivingEntity, BodyPlan> INSTANCE_PLANS = Collections.synchronizedMap(new WeakHashMap<>());

    private PlanRegistry() {
    }

    /** 服务端：接收上报 */
    public static void accept(ResourceLocation type, BodyPlan plan) {
        if (plan.boxCount() == 0 || !plan.isValid()) {
            CorpseLab.LOGGER.warn("拒绝骨架上报 {}：盒子数量 {}", type, plan.boxCount());
            return;
        }
        BodyPlan previous = PLANS.putIfAbsent(type, plan);
        if (previous == null) {
            CorpseLab.LOGGER.info("登记骨架 {}：{} 盒 / {} 关节",
                    type, plan.boxCount(), plan.jointCount());
        }
    }

    /** 服务端：按类型取骨架，没有则返回 null */
    public static BodyPlan get(ResourceLocation type) {
        return PLANS.get(type);
    }

    /** 服务端：记录当前实体经渲染器缩放后的实际骨架 */
    public static void acceptInstance(LivingEntity entity, BodyPlan plan) {
        BodyPlan base = PLANS.get(EntityType.getKey(entity.getType()));
        if (!plan.isValid() || !matchesBase(base, plan)) {
            return;
        }
        INSTANCE_PLANS.put(entity, plan);
    }

    /** 服务端：优先取当前实体的实际渲染尺寸，未上报时回退到类型级骨架 */
    public static BodyPlan get(LivingEntity entity) {
        BodyPlan supplied = com.pyrostella.corpselab.api.CorpseSkeletons.get(entity);
        if (supplied != null) return supplied;
        BodyPlan instance = INSTANCE_PLANS.get(entity);
        return instance != null ? instance : get(EntityType.getKey(entity.getType()));
    }

    private static boolean matchesBase(BodyPlan base, BodyPlan plan) {
        if (base == null || plan.boxCount() != base.boxCount()
                || !plan.joints().equals(base.joints())) {
            return false;
        }
        for (int i = 0; i < base.boxCount(); i++) {
            BodyPlan.Box expected = base.boxes().get(i);
            BodyPlan.Box actual = plan.boxes().get(i);
            if (actual.index() != i || !actual.path().equals(expected.path())) {
                return false;
            }
        }
        return true;
    }

    /** 发现数据属于本次服务器会话，模组注册的提供器由各自注册器管理 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PLANS.clear();
        INSTANCE_PLANS.clear();
    }

    public static int size() {
        return PLANS.size();
    }
}
