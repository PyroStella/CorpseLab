package com.pyrostella.corpselab.client.model;

import com.pyrostella.corpselab.util.ExtensionRegistry;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/** 仅客户端使用，选择优先级最高的匹配实现；第三方适配器通常使用正优先级 */
public final class CorpseModelAdapters {
    private static final ExtensionRegistry<CorpseModelAdapter> REGISTRY = new ExtensionRegistry<>();

    static {
        register(new ResourceLocation("corpselab", "vanilla"), 0, new VanillaModelAdapter());
        // 条件分支外不解析兼容实现，未安装时不会链接任何 GeckoLib 类型
        if (net.minecraftforge.fml.ModList.get().isLoaded("geckolib")) {
            register(new ResourceLocation("corpselab", "geckolib"), 100,
                    new com.pyrostella.corpselab.client.model.gecko.GeckoModelAdapter());
        }
    }

    private CorpseModelAdapters() {
    }

    /** 应在客户端初始化阶段、连接世界之前注册，避免改变服务端已接收的类型骨架 */
    public static ExtensionRegistry.Registration register(ResourceLocation id, int priority,
                                                          CorpseModelAdapter adapter) {
        return REGISTRY.register(id.toString(), priority, adapter);
    }

    @Nullable
    public static CorpseModelAdapter select(LivingEntity entity, EntityRenderer<?> renderer) {
        for (var entry : REGISTRY.entries()) {
            if (entry.value().supports(entity, renderer)) {
                return entry.value();
            }
        }
        return null;
    }

    /** 快照引用在注册或撤销时变化，供缓存判断绑定是否失效 */
    public static Object version() {
        return REGISTRY.entries();
    }

    public static boolean needsDiscovery() {
        return REGISTRY.entries().stream().anyMatch(entry -> !entry.value().usesLivingRenderEvent());
    }
}
