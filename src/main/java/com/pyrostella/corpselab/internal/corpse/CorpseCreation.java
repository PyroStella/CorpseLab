package com.pyrostella.corpselab.internal.corpse;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.api.event.CorpseCreateEvent;
import com.pyrostella.corpselab.api.event.CorpseRemovalEvent.Cause;
import com.pyrostella.corpselab.internal.config.CorpseLabConfig;
import com.pyrostella.corpselab.internal.config.PlanConfig;
import com.pyrostella.corpselab.api.model.BodyPlan;
import com.pyrostella.corpselab.internal.skeleton.PlanRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.Nullable;
import java.util.*;

import com.pyrostella.corpselab.api.CorpseView;

/** 尸体创建事务：世界入场、物品交接与失败回滚 */
public final class CorpseCreation {
    private CorpseCreation() { }

    /** 创建尸体快照，不杀死源实体；玩家物品遵守 keepInventory，失败返回空 */
    public static Optional<CorpseView> create(LivingEntity source) {
        return source == null ? Optional.empty() : create(source, CorpseSourceData.velocity(source));
    }

    /** 初速度单位为格每秒，生物骨架使用当前注册结果 */
    public static Optional<CorpseView> create(LivingEntity source, Vec3 velocity) {
        if (!CorpseOperations.canModify(source)) return Optional.empty();
        return create(source, source instanceof Player ? null : PlanRegistry.get(source), velocity);
    }

    /** 生物可显式提供骨架；玩家使用内置人形骨架，plan 应为 null */
    public static Optional<CorpseView> create(LivingEntity source, BodyPlan plan, Vec3 velocity) {
        if (!CorpseOperations.canModify(source) || velocity == null || !Double.isFinite(velocity.x)
                || !Double.isFinite(velocity.y) || !Double.isFinite(velocity.z)) return Optional.empty();
        ServerLevel level = (ServerLevel) source.level();
        if (source instanceof Player player) {
            if (plan != null || player.isSpectator() || !CorpseLabConfig.SERVER.corpseEnabled.get())
                return Optional.empty();
            CorpseEntity corpse = CorpseEntity.of(player, velocity);
            CorpseLifecycle.beginCreation(corpse);
            boolean committed = false;
            try {
                if (MinecraftForge.EVENT_BUS.post(
                        new CorpseCreateEvent.Pre(corpse,source))) return Optional.empty();
                if (!CorpseOperations.canModify(source) || corpse.isRemoved() || !level.addFreshEntity(corpse) || corpse.isRemoved()) return Optional.empty();
                if (!level.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
                    var inventory = player.getInventory();
                    List<net.minecraft.world.item.ItemStack> before = new ArrayList<>();
                    for (int i=0;i<inventory.getContainerSize();i++) before.add(inventory.getItem(i).copy());
                    try {
                        corpse.takeInventoryFrom(player);
                        if (corpse.isRemoved()) throw new IllegalStateException("物品交接期间尸体被移除");
                    } catch (RuntimeException failure) {
                        for (int i=0;i<before.size();i++) inventory.setItem(i,before.get(i));
                        // 清除回退尸体中的副本，避免外部保留引用后再次取出
                        for (int i=0;i<corpse.contents().getContainerSize();i++) corpse.contents().removeItemNoUpdate(i);
                        CorpseLab.LOGGER.error("尸体物品交接失败，已还原玩家背包",failure);
                        return Optional.empty();
                    }
                }
                CorpseLifecycle.commitCreation(corpse);
                committed = true;
            } finally {
                if (!committed) {
                    CorpseLifecycle.abortCreation(corpse);
                    corpse.discard();
                }
            }
            MinecraftForge.EVENT_BUS.post(
                    new CorpseCreateEvent.Post(corpse,source));
            return CorpseViews.find(corpse);
        }
        if (plan == null || !plan.isValid() || plan.boxCount() == 0) return Optional.empty();
        return CorpseViews.find(PlanCorpseEntity.spawn(level, plan, EntityType.getKey(source.getType()),
                source.position(), source.yBodyRot, velocity, source));
    }


    /** 生物创建的提交点与玩家一致，失败不淘汰旧尸体 */
    static PlanCorpseEntity createMob(ServerLevel level, BodyPlan plan, ResourceLocation type,
                                      Vec3 position, float yaw, Vec3 velocity, @Nullable LivingEntity source) {
        if (!level.getServer().isSameThread() || plan == null || plan.boxCount() == 0 || !plan.isValid()
                || !finite(position) || !Float.isFinite(yaw) || velocity != null && !finite(velocity)) return null;
        PlanCorpseEntity corpse = PlanCorpseEntity.prepare(level, plan, type, position, yaw, velocity, source);
        if (corpse == null) return null;
        CorpseLifecycle.beginCreation(corpse);
        boolean committed = false;
        try {
            if (MinecraftForge.EVENT_BUS.post(new CorpseCreateEvent.Pre(corpse, source))) return null;
            if (corpse.isRemoved() || !level.addFreshEntity(corpse) || corpse.isRemoved()) return null;
            CorpseLifecycle.commitCreation(corpse);
            committed = true;
        } finally {
            if (!committed) {
                CorpseLifecycle.abortCreation(corpse);
                corpse.discard();
            }
        }
        // 先通知本次提交，再进行会触发其它尸体退出回调的区域淘汰
        MinecraftForge.EVENT_BUS.post(new CorpseCreateEvent.Post(corpse, source));
        evictOldest(level, position, corpse);
        return corpse;
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static void evictOldest(ServerLevel level, Vec3 position, PlanCorpseEntity created) {
        if (created.isRemoved() || !CorpseLifecycle.isCommitted(created)) return;
        List<PlanCorpseEntity> existing = level.getEntitiesOfClass(PlanCorpseEntity.class,
                new AABB(position, position).inflate(96.0D), other -> other != created && !other.isRemoved());
        existing.sort(Comparator.comparingInt(PlanCorpseEntity::age).reversed());
        int maximum = PlanConfig.maxPerArea();
        while (!created.isRemoved() && CorpseLifecycle.isCommitted(created)) {
            // 退出回调可能删除其它候选，或嵌套创建并淘汰本次尸体
            existing.removeIf(PlanCorpseEntity::isRemoved);
            if (existing.size() + 1 <= maximum) return;
            CorpseLifecycle.discard(existing.remove(0), Cause.LIMIT);
        }
    }
}
