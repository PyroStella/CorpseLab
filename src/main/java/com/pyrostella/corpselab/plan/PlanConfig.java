package com.pyrostella.corpselab.plan;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 通用尸体系统的配置。
 *
 * <p>单独一份 spec，不塞进从 Tumble 继承的那份大配置里，避免改动原有逻辑。</p>
 */
public final class PlanConfig {

    /** 布娃娃形态 */
    public enum PlanMode {
        /**
         * 铰接：只有根节点自由移动，其余部件的位置由父节点锚点决定，只能绕关节转动。
         * 表现是"一具有关节的尸体"，永远不会散开。
         */
        ARTICULATED,
        /**
         * 自由：每个部件都是独立刚体，靠关节软约束拉在一起。
         * 冲击够大时关节会被拉开，看起来更"碎"。
         */
        FREE
    }

    private static final ForgeConfigSpec.EnumValue<PlanMode> MODE;
    private static final ForgeConfigSpec.IntValue MAX_AGE_TICKS;
    private static final ForgeConfigSpec.DoubleValue ACTIVE_RADIUS;
    private static final ForgeConfigSpec.IntValue MAX_PER_AREA;

    public static final ForgeConfigSpec SPEC;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("plan_corpse");
        MODE = builder
                .comment("布娃娃形态。",
                        "ARTICULATED = 不散架：只有根节点自由移动，其余部件只能绕关节转动。",
                        "FREE = 自由：每个部件独立受力，靠关节软约束拉在一起，冲击大时会被拽开。")
                .defineEnum("mode", PlanMode.ARTICULATED);

        MAX_AGE_TICKS = builder
                .comment("尸体存在多少 tick 后消失。20 tick = 1 秒，默认 2400 = 2 分钟。")
                .defineInRange("maxAgeTicks", 2400, 20, 240000);

        ACTIVE_RADIUS = builder
                .comment("多少格内有玩家才跑物理。远处的尸体冻结，省 CPU；玩家走近会接着模拟。")
                .defineInRange("activeRadius", 64.0D, 8.0D, 256.0D);

        MAX_PER_AREA = builder
                .comment("同一片区域最多保留多少具尸体，超出时删最老的。")
                .defineInRange("maxCorpses", 32, 1, 512);
        builder.pop();

        SPEC = builder.build();
    }

    private PlanConfig() {
    }

    public static PlanMode mode() {
        return MODE.get();
    }

    public static int maxAgeTicks() {
        return MAX_AGE_TICKS.get();
    }

    public static double activeRadius() {
        return ACTIVE_RADIUS.get();
    }

    public static int maxPerArea() {
        return MAX_PER_AREA.get();
    }
}
