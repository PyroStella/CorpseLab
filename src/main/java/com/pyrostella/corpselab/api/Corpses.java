package com.pyrostella.corpselab.api;

import com.pyrostella.corpselab.internal.config.CorpseLabConfig;
import com.pyrostella.corpselab.internal.corpse.CorpseEntity;
import com.pyrostella.corpselab.internal.corpse.CorpseLimbEntity;
import com.pyrostella.corpselab.internal.corpse.GrabbableCorpse;
import com.pyrostella.corpselab.internal.corpse.PlanCorpseEntity;
import com.pyrostella.corpselab.api.model.BodyPlan;
import com.pyrostella.corpselab.internal.network.BodyPlanCodec;
import com.pyrostella.corpselab.internal.corpse.CorpseSourceData;
import com.pyrostella.corpselab.internal.skeleton.PlanRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** 附属模组入口；操作返回是否成功，修改只在服务端主线程执行 */
public final class Corpses {
    private Corpses() { }

    private static Entity resolve(Entity entity) {
        return entity instanceof CorpseLimbEntity limb ? limb.corpse() : entity;
    }

    /** 肢体解析为所属尸体；视图跟随实体状态，部件数据为只读快照 */
    public static Optional<CorpseView> find(Entity entity) {
        entity = resolve(entity);
        return entity instanceof CorpseEntity || entity instanceof PlanCorpseEntity
                ? Optional.of(new View(entity)) : Optional.empty();
    }

    /** 创建尸体快照，不杀死源实体；玩家物品遵守 keepInventory，失败返回空 */
    public static Optional<CorpseView> create(LivingEntity source) {
        return source == null ? Optional.empty() : create(source, CorpseSourceData.velocity(source));
    }

    /** 初速度单位为格每秒，生物骨架使用当前注册结果 */
    public static Optional<CorpseView> create(LivingEntity source, Vec3 velocity) {
        if (!canModify(source)) return Optional.empty();
        return create(source, source instanceof Player ? null : PlanRegistry.get(source), velocity);
    }

    /** 生物可显式提供骨架；玩家使用内置人形骨架，plan 应为 null */
    public static Optional<CorpseView> create(LivingEntity source, BodyPlan plan, Vec3 velocity) {
        if (!canModify(source) || velocity == null || !Double.isFinite(velocity.x)
                || !Double.isFinite(velocity.y) || !Double.isFinite(velocity.z)) return Optional.empty();
        ServerLevel level = (ServerLevel) source.level();
        if (source instanceof Player player) {
            if (plan != null || player.isSpectator() || !CorpseLabConfig.SERVER.corpseEnabled.get())
                return Optional.empty();
            CorpseEntity corpse = CorpseEntity.of(player, velocity);
            boolean committed = false;
            try {
                if (net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                        new com.pyrostella.corpselab.api.event.CorpseCreateEvent.Pre(corpse,source))) return Optional.empty();
                if (!canModify(source) || corpse.isRemoved() || !level.addFreshEntity(corpse) || corpse.isRemoved()) return Optional.empty();
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
                        com.pyrostella.corpselab.CorpseLab.LOGGER.error("尸体物品交接失败，已还原玩家背包",failure);
                        return Optional.empty();
                    }
                }
                committed = true;
            } finally {
                if (!committed) corpse.discard();
            }
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                    new com.pyrostella.corpselab.api.event.CorpseCreateEvent.Post(corpse,source));
            return find(corpse);
        }
        if (plan == null || !BodyPlanCodec.valid(plan) || plan.boxCount() == 0) return Optional.empty();
        return find(PlanCorpseEntity.spawn(level, plan, EntityType.getKey(source.getType()),
                source.position(), source.yBodyRot, velocity, source));
    }

    /** 仅检查是否能开始复活，不改变状态 */
    public static boolean canRevive(Entity entity) {
        return revivalProblem(entity).isEmpty();
    }

    /** 启动默认两秒动画，true 表示成功开始，不表示动画已经结束 */
    public static boolean revive(Entity entity) { return revive(entity, 40); }

    public static boolean revive(Entity entity, int durationTicks) {
        Entity corpse = resolve(entity);
        return durationTicks > 0 && revive(corpse, RevivalOptions.defaults().duration(durationTicks));
    }

    /** 自定义基准与目标姿态；未指定的选项保持默认行为 */
    public static boolean revive(Entity entity, RevivalOptions options) {
        Entity corpse = resolve(entity);
        return options != null && canModify(corpse) && corpse instanceof PlanCorpseEntity mob
                && mob.beginRevival(options) == PlanCorpseEntity.RevivalResult.STARTED;
    }

    /** 停止正在进行的复活并保留尸体 */
    public static boolean stopRevival(Entity entity) {
        Entity corpse = resolve(entity);
        return canModify(corpse) && corpse instanceof PlanCorpseEntity mob && mob.stopRevival();
    }

    /** 按玩家视线抓取指定尸体；占用、超距或不可抓取时返回 false */
    public static boolean tryGrab(Entity entity, Player player) {
        var view = find(entity);
        if (view.isEmpty()) return false;
        Entity corpse = view.get().entity();
        if (!canModify(corpse) || player == null || player.level() != corpse.level()
                || !player.isAlive() || player.isSpectator() || !CorpseLabConfig.SERVER.grabEnabled.get()) return false;
        if (view.get().state() == CorpseView.State.REVIVING || view.get().grab().isPresent()) return false;
        GrabbableCorpse grabbable = (GrabbableCorpse) corpse;
        if (!grabbable.tryGrab(player)) return false;
        grabbable.wakeForGrab();
        return true;
    }

    /** 释放抓取；没有抓取关系时返回 false */
    public static boolean releaseGrab(Entity entity) {
        var view = find(entity);
        if (view.isEmpty() || !canModify(view.get().entity()) || view.get().grab().isEmpty()) return false;
        ((GrabbableCorpse) view.get().entity()).releaseGrab();
        return true;
    }

    private static boolean canModify(Entity entity) {
        return entity != null && entity.level() instanceof ServerLevel level && level.getServer().isSameThread() && !entity.isRemoved();
    }

    /** 可选的诊断入口，供命令或界面显示；可复活时返回空，不要求调用方处理错误码 */
    public static Optional<RevivalProblem> revivalProblem(Entity entity) {
        Entity corpse = resolve(entity);
        RevivalProblem problem;
        if (corpse == null || find(corpse).isEmpty()) problem = RevivalProblem.NOT_CORPSE;
        else if (!(corpse.level() instanceof ServerLevel level)) problem = RevivalProblem.SERVER_ONLY;
        else if (!level.getServer().isSameThread()) problem = RevivalProblem.MAIN_THREAD;
        else if (corpse.isRemoved()) problem = RevivalProblem.REMOVED;
        else if (!(corpse instanceof PlanCorpseEntity mob)) problem = RevivalProblem.UNSUPPORTED;
        else problem = switch (mob.revivalReadiness()) {
            case STARTED -> null;
            case ALREADY_REVIVING -> RevivalProblem.ALREADY_REVIVING;
            case MISSING_DATA -> RevivalProblem.MISSING_DATA;
            case UUID_IN_USE -> RevivalProblem.UUID_IN_USE;
            case INVALID_ENTITY -> RevivalProblem.INVALID_ENTITY;
        };
        return Optional.ofNullable(problem);
    }

    private record View(Entity entity) implements CorpseView {
        public Kind kind() { return entity instanceof CorpseEntity ? Kind.PLAYER : Kind.MOB; }
        public net.minecraft.resources.ResourceLocation sourceType() {
            return entity instanceof PlanCorpseEntity mob ? mob.sourceType() : EntityType.getKey(EntityType.PLAYER);
        }
        public Optional<UUID> sourceId() {
            if (entity instanceof CorpseEntity player) return Optional.ofNullable(player.getOwnerId());
            var data = ((PlanCorpseEntity) entity).sourceEntityData();
            return data.hasUUID("UUID") ? Optional.of(data.getUUID("UUID")) : Optional.empty();
        }
        public Optional<UUID> ownerId() {
            return entity instanceof CorpseEntity player ? Optional.ofNullable(player.getOwnerId()) : Optional.empty();
        }
        public int ageTicks() { return entity instanceof CorpseEntity player ? player.age() : ((PlanCorpseEntity) entity).age(); }
        public State state() {
            if (entity.isRemoved()) return State.REMOVED;
            if (entity instanceof PlanCorpseEntity mob && mob.isReviving()) return State.REVIVING;
            if (entity.level().isClientSide) return State.UNKNOWN;
            if (grab().isPresent()) return State.GRABBED;
            return entity instanceof CorpseEntity player && player.isFrozen() ? State.FROZEN : State.ACTIVE;
        }
        public boolean supportsRevival() { return entity instanceof PlanCorpseEntity; }
        public boolean supportsLooting() { return entity instanceof CorpseEntity; }
        public List<Part> parts() {
            if (entity.level().isClientSide) return List.of();
            List<com.pyrostella.corpselab.physics.RigidBody> bodies;
            if (entity instanceof PlanCorpseEntity mob) bodies = mob.rigidBodies();
            else {
                var skeleton = ((CorpseEntity) entity).skeleton();
                if (skeleton == null) return List.of();
                bodies = skeleton.bodies();
            }
            List<Part> parts = new ArrayList<>();
            for (int i = 0; i < bodies.size(); i++) {
                var body = bodies.get(i);
                String id = entity instanceof PlanCorpseEntity mob ? mob.plan().boxes().get(i).path()
                        : com.pyrostella.corpselab.api.model.BodyPart.values()[i].name().toLowerCase(Locale.ROOT);
                float scale = entity instanceof PlanCorpseEntity mob ? mob.limbScale(i) : 1;
                parts.add(new Part(i, id, new Vec3(body.pos.x, body.pos.y, body.pos.z),
                        new Rotation(body.rot.x, body.rot.y, body.rot.z, body.rot.w),
                        new Vec3(body.halfExtents.x, body.halfExtents.y, body.halfExtents.z), scale));
            }
            return List.copyOf(parts);
        }
        public int basePartIndex() {
            if (entity instanceof PlanCorpseEntity mob && !entity.level().isClientSide) return mob.revivalBasePartIndex();
            var parts = parts();
            int index = -1; double volume = -1;
            for (var part : parts) {
                Vec3 half = part.halfExtents(); double current = half.x * half.y * half.z;
                if (current > volume) { volume = current; index = part.index(); }
            }
            return index;
        }
        public Optional<Grab> grab() {
            if (entity.level().isClientSide) return Optional.empty();
            return entity instanceof PlanCorpseEntity mob ? mob.grabInfo() : ((CorpseEntity) entity).grabInfo();
        }
        public Optional<Revival> revival() {
            return entity instanceof PlanCorpseEntity mob && !entity.level().isClientSide
                    ? mob.revivalInfo() : Optional.empty();
        }
    }
}
