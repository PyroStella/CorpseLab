package com.pyrostella.corpselab.internal.corpse;

import com.pyrostella.corpselab.internal.config.CorpseLabConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import java.util.*;

import com.pyrostella.corpselab.api.CorpseView;
import com.pyrostella.corpselab.api.RevivalOptions;
import com.pyrostella.corpselab.api.RevivalProblem;

/** 公共命令的线程、能力与生命周期边界 */
public final class CorpseOperations {
    private CorpseOperations() { }

    /** 仅检查是否能开始复活，不改变状态 */
    public static boolean canRevive(Entity entity) {
        return revivalProblem(entity).isEmpty();
    }

    /** 启动默认两秒动画，true 表示成功开始，不表示动画已经结束 */
    public static boolean revive(Entity entity) { return revive(entity, 40); }

    public static boolean revive(Entity entity, int durationTicks) {
        Entity corpse = CorpseViews.resolve(entity);
        return durationTicks > 0 && revive(corpse, RevivalOptions.defaults().duration(durationTicks));
    }

    /** 自定义基准与目标姿态；未指定的选项保持默认行为 */
    public static boolean revive(Entity entity, RevivalOptions options) {
        Entity corpse = CorpseViews.resolve(entity);
        return options != null && canOperate(corpse) && corpse instanceof PlanCorpseEntity mob
                && mob.beginRevival(options) == PlanCorpseEntity.RevivalResult.STARTED;
    }

    /** 停止正在进行的复活并保留尸体 */
    public static boolean stopRevival(Entity entity) {
        Entity corpse = CorpseViews.resolve(entity);
        return canOperate(corpse) && corpse instanceof PlanCorpseEntity mob && mob.stopRevival();
    }

    /** 按玩家视线抓取指定尸体；占用、超距或不可抓取时返回 false */
    public static boolean tryGrab(Entity entity, Player player) {
        var view = CorpseViews.find(entity);
        if (view.isEmpty()) return false;
        Entity corpse = view.get().entity();
        if (!canOperate(corpse) || player == null || player.level != corpse.level
                || !player.isAlive() || player.isSpectator() || !CorpseLabConfig.SERVER.grabEnabled.get()) return false;
        if (view.get().state() == CorpseView.State.REVIVING || view.get().grab().isPresent()) return false;
        GrabbableCorpse grabbable = (GrabbableCorpse) corpse;
        if (!grabbable.tryGrab(player)) return false;
        grabbable.wakeForGrab();
        return true;
    }

    /** 释放抓取；没有抓取关系时返回 false */
    public static boolean releaseGrab(Entity entity) {
        var view = CorpseViews.find(entity);
        if (view.isEmpty() || !canOperate(view.get().entity()) || view.get().grab().isEmpty()) return false;
        ((GrabbableCorpse) view.get().entity()).releaseGrab();
        return true;
    }

    static boolean canModify(Entity entity) {
        return entity != null && entity.level instanceof ServerLevel level && level.getServer().isSameThread() && !entity.isRemoved();
    }

    private static boolean canOperate(Entity entity) {
        return canModify(entity) && CorpseLifecycle.isCommitted(entity);
    }

    /** 可选的诊断入口，供命令或界面显示；可复活时返回空，不要求调用方处理错误码 */
    public static Optional<RevivalProblem> revivalProblem(Entity entity) {
        Entity corpse = CorpseViews.resolve(entity);
        RevivalProblem problem;
        if (corpse == null || CorpseViews.find(corpse).isEmpty()) problem = RevivalProblem.NOT_CORPSE;
        else if (!(corpse.level instanceof ServerLevel level)) problem = RevivalProblem.SERVER_ONLY;
        else if (!level.getServer().isSameThread()) problem = RevivalProblem.MAIN_THREAD;
        else if (corpse.isRemoved()) problem = RevivalProblem.REMOVED;
        else if (!(corpse instanceof PlanCorpseEntity mob)) problem = RevivalProblem.UNSUPPORTED;
        else if (!CorpseLifecycle.isCommitted(corpse)) problem = RevivalProblem.INVALID_ENTITY;
        else problem = switch (mob.revivalReadiness()) {
            case STARTED -> null;
            case ALREADY_REVIVING -> RevivalProblem.ALREADY_REVIVING;
            case MISSING_DATA -> RevivalProblem.MISSING_DATA;
            case UUID_IN_USE -> RevivalProblem.UUID_IN_USE;
            case INVALID_ENTITY -> RevivalProblem.INVALID_ENTITY;
        };
        return Optional.ofNullable(problem);
    }

}
