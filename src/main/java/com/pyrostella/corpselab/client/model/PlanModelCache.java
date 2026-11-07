package com.pyrostella.corpselab.client.model;

import com.pyrostella.corpselab.api.client.CorpseModelAdapter;
import com.pyrostella.corpselab.api.client.CorpseModelAdapters;
import com.pyrostella.corpselab.internal.corpse.CorpseAppearance;
import com.pyrostella.corpselab.internal.corpse.PlanCorpseEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/** 每具尸体独立缓存外观探针和模型绑定，避免同类尸体串用外观 */
public final class PlanModelCache {
    private static final Map<PlanCorpseEntity, CorpseBinding> CORPSES = new WeakHashMap<>();
    private static Object adaptersVersion;

    private PlanModelCache() {
    }

    @Nullable
    public static CorpseModelAdapter.Binding get(PlanCorpseEntity corpse, EntityType<?> type) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || corpse.level() != minecraft.level) {
            return null;
        }
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
        CorpseModelAdapter.Binding binding = build(corpse, type);
        // 第三方模型可能尚未就绪，失败绑定每 20 tick 重试
        CORPSES.put(corpse, new CorpseBinding(corpse.appearanceVersion(), binding,
                corpse.level().getGameTime() + 20));
        return binding;
    }

    @Nullable
    private static CorpseModelAdapter.Binding build(PlanCorpseEntity corpse, EntityType<?> type) {
        Entity created = type.create(corpse.level());
        if (!(created instanceof LivingEntity probe)) {
            return null;
        }
        CorpseAppearance.apply(probe, corpse.appearance());
        EntityRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(probe);
        CorpseModelAdapter adapter = CorpseModelAdapters.select(probe, renderer);
        return adapter == null ? null : adapter.bind(probe, renderer);
    }

    public static void clear() {
        CORPSES.clear();
        adaptersVersion = null;
    }

    private record CorpseBinding(int version, @Nullable CorpseModelAdapter.Binding binding, long retryAt) {
    }
}
