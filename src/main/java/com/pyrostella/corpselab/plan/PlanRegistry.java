package com.pyrostella.corpselab.plan;

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
 * <p class="note">服务端信任客户端发来的尺寸，这是当前的安全缺口：恶意客户端可以上报超大的盒子
 * 后续要按生物命中箱做 clamp，并限制盒子上限。</p>
 */
public final class PlanRegistry {

    /** 盒子数量上限，防止有人塞一万个盒子把服务端拖死 */
    public static final int MAX_BOXES = 96;

    private static final Map<ResourceLocation, BodyPlan> PLANS = new ConcurrentHashMap<>();
    private static final Map<LivingEntity, BodyPlan> INSTANCE_PLANS = Collections.synchronizedMap(new WeakHashMap<>());

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

    /** 服务端：记录当前实体经渲染器缩放后的实际骨架 */
    public static void acceptInstance(LivingEntity entity, BodyPlan plan) {
        BodyPlan base = PLANS.get(EntityType.getKey(entity.getType()));
        if (!matchesBase(base, plan)) {
            return;
        }
        INSTANCE_PLANS.put(entity, plan);
    }

    /** 服务端：优先取当前实体的实际渲染尺寸，未上报时回退到类型级骨架 */
    public static BodyPlan get(LivingEntity entity) {
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
            if (actual.index() != i || !actual.path().equals(expected.path())
                    || !finite(actual) || !reasonableScale(expected, actual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean finite(BodyPlan.Box box) {
        return Double.isFinite(box.cx()) && Double.isFinite(box.cy()) && Double.isFinite(box.cz())
                && Double.isFinite(box.hx()) && Double.isFinite(box.hy()) && Double.isFinite(box.hz())
                && Double.isFinite(box.mass())
                && Double.isFinite(box.px()) && Double.isFinite(box.py()) && Double.isFinite(box.pz());
    }

    private static boolean reasonableScale(BodyPlan.Box base, BodyPlan.Box actual) {
        return scaleInRange(actual.hx(), base.hx())
                && scaleInRange(actual.hy(), base.hy())
                && scaleInRange(actual.hz(), base.hz());
    }

    private static boolean scaleInRange(double actual, double base) {
        if (base <= 1.0E-6D) {
            return actual >= 0.0D && actual <= 1.0E-3D;
        }
        double scale = actual / base;
        return scale >= 0.03125D && scale <= 32.0D;
    }

    public static int size() {
        return PLANS.size();
    }
}
