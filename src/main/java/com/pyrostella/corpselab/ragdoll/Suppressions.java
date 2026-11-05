package com.pyrostella.corpselab.ragdoll;

import com.pyrostella.corpselab.config.CorpseLabConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家绝不应被自动掀倒的若干状态
 *
 * <p>这份列表是触发系统里最不显眼也最重要的部分。黏液块弹射、激流三叉戟
 * 和鞘翅起飞在物理上看起来与猛烈撞击完全一样，若没有这些检查
 * 玩家每次做游戏本就允许的动作都会被按到地上
 * 抑制只对自动触发生效：用按键主动要求倒下的玩家
 * 永远都可以
 *
 * <p>每个状态还会留下一段宽限期，因为危险的时刻通常在其之后
 * 是弹起的那一刻，而不是方块本身
 */
public final class Suppressions {

    private static final Map<UUID, Integer> GRACE = new HashMap<>();

    private Suppressions() {
    }

    /** 每个服务端 tick 对每个玩家调用一次，用于维护宽限期倒计时 */
    public static void tick(ServerPlayer player) {
        if (inSuppressedState(player)) {
            GRACE.put(player.getUUID(), CorpseLabConfig.SERVER.suppressionGraceTicks.get());
            return;
        }
        Integer remaining = GRACE.get(player.getUUID());
        if (remaining == null) {
            return;
        }
        if (remaining <= 1) {
            GRACE.remove(player.getUUID());
        } else {
            GRACE.put(player.getUUID(), remaining - 1);
        }
    }

    public static boolean isSuppressed(ServerPlayer player) {
        return inSuppressedState(player) || GRACE.containsKey(player.getUUID());
    }

    public static void forget(UUID playerId) {
        GRACE.remove(playerId);
    }

    private static boolean inSuppressedState(ServerPlayer player) {
        CorpseLabConfig.Server config = CorpseLabConfig.SERVER;

        if (config.suppressRiptide.get() && player.isAutoSpinAttack()) {
            return true;
        }
        if (config.suppressElytraFlight.get() && player.isFallFlying()) {
            return true;
        }
        if (config.suppressCreativeFlight.get() && player.getAbilities().flying) {
            return true;
        }
        if (config.suppressClimbing.get() && player.onClimbable()) {
            return true;
        }
        if (config.suppressWater.get() && inWater(player)) {
            return true;
        }
        return config.suppressBounce.get() && onBouncyBlock(player);
    }

    /**
     * 落水刹停正是这个方法存在的理由：原版中长距离坠入水池不会受伤
     * 但撞上水面是极大的速度变化，而这恰恰是撞击触发器
     * 在盯的东西
     *
     * <p>除了实体标志位之外还会检查方块，因为标志位要等玩家真正
     * 进入流体之后才亮起，而撞击是在入水途中判定的
     */
    private static boolean inWater(ServerPlayer player) {
        if (player.isInWater()) {
            return true;
        }
        return player.level().getFluidState(player.blockPosition()).is(FluidTags.WATER)
                || player.level().getFluidState(player.blockPosition().below()).is(FluidTags.WATER);
    }

    /**
     * 黏液块会取消坠落伤害，却产生巨大的速度反向，这正是撞击触发器
     * 要找的东西。在方块这一层拦住它，才能避免一次弹跳
     * 被当成坠毁
     */
    private static boolean onBouncyBlock(ServerPlayer player) {
        BlockState below = player.getBlockStateOn();
        return below.is(Blocks.SLIME_BLOCK) || below.is(BlockTags.BEDS);
    }
}
