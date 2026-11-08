package com.pyrostella.corpselab.api.environment;

import com.pyrostella.corpselab.api.CorpseView;
import com.pyrostella.corpselab.api.Registration;
import com.pyrostella.corpselab.internal.environment.LimbEffectSession;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import java.util.function.Supplier;

/** 环境扩展入口：低优先级先执行，高优先级可覆盖环境参数或补充施力 */
public final class LimbEffects {
    private LimbEffects() { }

    /** 每个肢体独立创建处理器；注册变化也会应用到既有肢体 */
    public static Registration register(ResourceLocation id, int priority,
                                        Supplier<? extends LimbEnvironmentEffect> factory) {
        return LimbEffectSession.register(id, priority, factory);
    }

    /** 行为只依赖所需环境能力，不持有内部尸体或求解器 */
    public interface Context {
        Entity limb();
        CorpseView corpse();
        boolean onGround();

        /** 仅在 tick 中设置，所有行为完成后统一写入；阻尼范围为 0 到 1 */
        void setPhysics(double gravityScale, double frictionScale, double supportDamping);
    }
}
