package com.pyrostella.corpselab.client.model;

import com.pyrostella.corpselab.api.client.CorpseModelAdapter;
import com.pyrostella.corpselab.api.client.CorpseModelAdapters;
import com.pyrostella.corpselab.internal.corpse.PlanCorpseEntity;
import com.pyrostella.corpselab.internal.corpse.CorpseAppearance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.*;


/**
 * 客户端缓存：生物类型 → 适配器生成的绘制绑定
 *
 * <p>模型体系的细节封装在绑定内，缓存层只负责创建探针实体、选择适配器和连接生命周期。</p>
 *
 * <p>这个缓存只在客户端存在，服务端完全不知道模型长什么样。</p>
 */
public final class PlanModelCache {

    private static final Map<EntityType<?>, CorpseModelAdapter.Binding> CACHE = new HashMap<>();
    private static final Map<EntityType<?>, Long> RETRY_AT = new HashMap<>();
    private static Object adaptersVersion;
    private record CorpseBinding(int version, CorpseModelAdapter.Binding binding, long retryAt) {
    }
    private static final Map<PlanCorpseEntity, CorpseBinding> CORPSES = new WeakHashMap<>();

    /** 不同尸体使用独立探针，避免职业、颜色和装备在同类尸体之间串用 */
    @Nullable
    public static CorpseModelAdapter.Binding get(PlanCorpseEntity corpse, EntityType<?> type) {
        if (adaptersVersion != CorpseModelAdapters.version()) {
            clear();
            adaptersVersion = CorpseModelAdapters.version();
        }
        CorpseBinding cached = CORPSES.get(corpse);
        if (cached != null && cached.version() == corpse.appearanceVersion()) {
            if (cached.binding() != null || corpse.level().getGameTime() < cached.retryAt()) {
                return cached.binding();
            }
        }
        CorpseModelAdapter.Binding binding = build(type, corpse.appearance());
        CORPSES.put(corpse, new CorpseBinding(corpse.appearanceVersion(), binding,
                corpse.level().getGameTime() + 20));
        return binding;
    }

    private PlanModelCache() {
    }

    @Nullable
    public static CorpseModelAdapter.Binding get(EntityType<?> type) {
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        if (adaptersVersion != CorpseModelAdapters.version()) {
            clear();
            adaptersVersion = CorpseModelAdapters.version();
        }
        if (CACHE.containsKey(type)) {
            return CACHE.get(type);
        }
        if (Minecraft.getInstance().level.getGameTime() < RETRY_AT.getOrDefault(type, Long.MIN_VALUE)) {
            return null;
        }

        CorpseModelAdapter.Binding binding = build(type);
        if (binding == null) {
            // 第三方模型可能尚未就绪，限频重试，不能永久缓存一次性的绑定失败
            RETRY_AT.put(type, Minecraft.getInstance().level.getGameTime() + 20);
        } else {
            RETRY_AT.remove(type);
            CACHE.put(type, binding);
        }
        return binding;
    }

    @Nullable
    private static CorpseModelAdapter.Binding build(EntityType<?> type) {
        return build(type, new CompoundTag());
    }

    @Nullable
    private static CorpseModelAdapter.Binding build(EntityType<?> type, CompoundTag appearance) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }

        Entity created = type.create(minecraft.level);
        if (!(created instanceof LivingEntity probe)) {
            return null;
        }
        CorpseAppearance.apply(probe, appearance);

        EntityRenderer<?> renderer = minecraft.getEntityRenderDispatcher().getRenderer(probe);
        CorpseModelAdapter adapter = CorpseModelAdapters.select(probe, renderer);
        return adapter == null ? null : adapter.bind(probe, renderer);
    }

    public static void clear() {
        CACHE.clear();
        RETRY_AT.clear();
        CORPSES.clear();
    }
}
