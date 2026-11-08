package com.pyrostella.corpselab.internal.corpse;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** 尸体退出战斗选取，搜刮通过独立射线保留距离与遮挡规则 */
public final class CorpseInteraction {
    private CorpseInteraction() {
    }

    /** 玩家触发的爆炸仍属于环境作用，普通攻击与投射物不推动尸体 */
    public static boolean isPlayerDamage(DamageSource source) {
        return source.getEntity() instanceof Player && !source.isExplosion();
    }

    @Nullable
    public static EntityHitResult pickLootTarget(Entity viewer, double reach) {
        if (!Double.isFinite(reach) || reach <= 0) {
            return null;
        }
        Vec3 start = viewer.getEyePosition();
        Vec3 direction = viewer.getViewVector(1.0F).scale(reach);
        Vec3 end = start.add(direction);
        HitResult block = viewer.pick(reach, 1.0F, false);
        double limit = block.getType() == HitResult.Type.MISS
                ? reach * reach : start.distanceToSqr(block.getLocation());
        // 同时寻找可选中的普通实体，不能隔着活体打开后面的尸体
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(viewer, start, end,
                viewer.getBoundingBox().expandTowards(direction).inflate(1.0D),
                entity -> !entity.isSpectator() && (entity.isPickable() || entity instanceof CorpseEntity),
                limit);
        return hit != null && hit.getEntity() instanceof CorpseEntity
                && start.distanceToSqr(hit.getLocation()) <= limit ? hit : null;
    }
}
