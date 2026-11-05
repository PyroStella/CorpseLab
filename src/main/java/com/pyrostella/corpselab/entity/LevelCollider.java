package com.pyrostella.corpselab.entity;

import com.pyrostella.corpselab.physics.Aabb;
import com.pyrostella.corpselab.physics.WorldCollider;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * 求解器与 Minecraft 世界之间唯一的桥梁
 *
 * <p>使用 level 自身的碰撞形状，因此半砖、楼梯、栅栏以及模组方块
 * 都在无需了解它们任何细节的情况下被处理
 */
public final class LevelCollider implements WorldCollider {

    private final Level level;

    public LevelCollider(Level level) {
        this.level = level;
    }

    @Override
    public void collectSolids(Aabb query, List<Aabb> out) {
        AABB box = new AABB(query.minX(), query.minY(), query.minZ(),
                query.maxX(), query.maxY(), query.maxZ());

        for (VoxelShape shape : level.getBlockCollisions(null, box)) {
            for (AABB part : shape.toAabbs()) {
                out.add(new Aabb(part.minX, part.minY, part.minZ, part.maxX, part.maxY, part.maxZ));
            }
        }
    }
}
