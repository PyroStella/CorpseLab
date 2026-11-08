package com.pyrostella.corpselab.api;

import com.pyrostella.corpselab.api.model.BodyPlan;
import com.pyrostella.corpselab.internal.corpse.CorpseCreation;
import com.pyrostella.corpselab.internal.corpse.CorpseOperations;
import com.pyrostella.corpselab.internal.corpse.CorpseViews;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;

/** 附属模组的统一入口，修改操作只在服务端主线程执行 */
public final class Corpses {
    private Corpses() { }

    /** 肢体自动解析为所属尸体；部件数据按需返回不可修改的快照 */
    public static Optional<CorpseView> find(Entity entity) {
        return CorpseViews.find(entity);
    }

    /** 不杀死来源；玩家遵守物品转移配置和 keepInventory，入场失败不搬动物品 */
    public static Optional<CorpseView> create(LivingEntity source) {
        return CorpseCreation.create(source);
    }

    /** 初速度单位为格每秒，生物使用注册或发现的骨架 */
    public static Optional<CorpseView> create(LivingEntity source, Vec3 velocity) {
        return CorpseCreation.create(source, velocity);
    }

    /** 生物可显式指定骨架，玩家使用内置骨架并要求 plan 为 null */
    public static Optional<CorpseView> create(LivingEntity source, BodyPlan plan, Vec3 velocity) {
        return CorpseCreation.create(source, plan, velocity);
    }

    public static boolean canRevive(Entity entity) { return CorpseOperations.canRevive(entity); }

    /** true 表示默认 40 tick 动画已开始，最终结果由复活事件通知 */
    public static boolean revive(Entity entity) { return CorpseOperations.revive(entity); }
    public static boolean revive(Entity entity, int durationTicks) { return CorpseOperations.revive(entity, durationTicks); }
    public static boolean revive(Entity entity, RevivalOptions options) { return CorpseOperations.revive(entity, options); }
    public static boolean stopRevival(Entity entity) { return CorpseOperations.stopRevival(entity); }

    /** 依据服务端视线抓取，不能抢占已经建立的关系 */
    public static boolean tryGrab(Entity entity, Player player) { return CorpseOperations.tryGrab(entity, player); }

    /** 强制释放这具尸体的抓取关系，不要求调用者为抓取玩家 */
    public static boolean releaseGrab(Entity entity) { return CorpseOperations.releaseGrab(entity); }

    /** 查询当前可行性，不保存上一次操作结果，也不预测事件否决 */
    public static Optional<RevivalProblem> revivalProblem(Entity entity) { return CorpseOperations.revivalProblem(entity); }
}
