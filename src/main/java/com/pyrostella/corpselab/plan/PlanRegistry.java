package com.pyrostella.corpselab.plan;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.pyrostella.corpselab.CorpseLab;

import net.minecraft.resources.ResourceLocation;

/**
 * 服务端缓存：生物类型 → 骨架。
 *
 * <p>数据由客户端上报（{@code PlanSubmitPacket}）。同类型只保留第一次的结果，
 * 防止不同客户端反复覆盖导致姿态漂移。</p>
 *
 * <p class="note">服务端信任客户端发来的尺寸，这是当前的安全缺口：恶意客户端可以上报超大的盒子。
 * 后续要按生物命中箱做 clamp，并限制盒子上限。</p>
 */
public final class PlanRegistry {

    /** 盒子数量上限，防止有人塞一万个盒子把服务端拖死 */
    public static final int MAX_BOXES = 96;

    private static final Map<ResourceLocation, BodyPlan> PLANS = new ConcurrentHashMap<>();

    private PlanRegistry() {
    }

    /** 服务端：接收上报 */
    public static void accept(ResourceLocation type, BodyPlan plan) {
        if (plan.boxCount() == 0 || plan.boxCount() > MAX_BOXES) {
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

    public static int size() {
        return PLANS.size();
    }
}
