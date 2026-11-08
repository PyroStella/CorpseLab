package com.pyrostella.corpselab.physics.collision;

import java.util.List;

/**
 * 提供布娃娃与之碰撞的实体几何
 *
 * <p>这里是求解器与 Minecraft 之间唯一的接缝。physics 包对 blocks
 * 和 chunks、levels 一无所知——只认识盒子——正因如此它才能脱离
 * 游戏被测试
 */
@FunctionalInterface
public interface WorldCollider {

    /** 把所有与 {@code query} 相交的实体盒追加到 {@code out} */
    void collectSolids(Aabb query, List<Aabb> out);

    WorldCollider EMPTY = (query, out) -> {
    };
}
