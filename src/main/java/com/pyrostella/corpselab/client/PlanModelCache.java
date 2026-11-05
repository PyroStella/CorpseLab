package com.pyrostella.corpselab.client;

import com.pyrostella.corpselab.plan.BodyPlan;
import com.pyrostella.corpselab.plan.ModelPartScanner;
import com.pyrostella.corpselab.plan.ModelPartScanner.PartRef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.*;


/**
 * 客户端缓存：生物类型 → 模型部件表
 *
 * <p>渲染尸体时要把刚体变换套回原模型，所以需要三样东西：模型的 {@code EntityModel}
 * 每个盒子对应的 {@code ModelPart}、以及一个能取贴图用的实体实例（贴图要靠实体算）。</p>
 *
 * <p>这个缓存只在客户端存在，服务端完全不知道模型长什么样。</p>
 */
public final class PlanModelCache {

    /**
     * @param parts    与 BodyPlan 盒子一一对应的模型部件
     * @param model    实体模型
     * @param renderer 原渲染器，用来取贴图
     * @param probe    用来取贴图的假实体
     */
    public record Binding(BodyPlan plan, List<PartRef> parts, EntityModel<?> model,
                          LivingEntityRenderer<?, ?> renderer, LivingEntity probe) {
    }

    private static final Map<EntityType<?>, Binding> CACHE = new HashMap<>();
    private static final Map<EntityType<?>, Boolean> FAILED = new HashMap<>();

    private PlanModelCache() {
    }

    @Nullable
    public static Binding get(EntityType<?> type) {
        if (CACHE.containsKey(type)) {
            return CACHE.get(type);
        }
        if (FAILED.containsKey(type)) {
            return null;
        }

        Binding binding = build(type);
        if (binding == null) {
            FAILED.put(type, true);
        } else {
            CACHE.put(type, binding);
        }
        return binding;
    }

    @Nullable
    private static Binding build(EntityType<?> type) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }

        Entity created = type.create(minecraft.level);
        if (!(created instanceof LivingEntity probe)) {
            return null;
        }
        probe.setPos(0.0D, -1000.0D, 0.0D);

        EntityRenderer<?> renderer = minecraft.getEntityRenderDispatcher().getRenderer(probe);
        if (!(renderer instanceof LivingEntityRenderer<?, ?> livingRenderer)) {
            return null;
        }

        EntityModel<?> model = livingRenderer.getModel();
        ModelPartScanner.ScanResult result = ModelPartScanner.scanDetailed(model);
        if (result.parts().isEmpty()) {
            return null;
        }

        return new Binding(result.plan(), result.parts(), model, livingRenderer, probe);
    }

    public static void clear() {
        CACHE.clear();
        FAILED.clear();
    }
}
