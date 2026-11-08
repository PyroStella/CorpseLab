package com.pyrostella.corpselab.internal.corpse;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.api.CorpseView;
import com.pyrostella.corpselab.api.Corpses;
import com.pyrostella.corpselab.api.RevivalOptions;
import com.pyrostella.corpselab.api.event.CorpseReviveEvent;
import com.pyrostella.corpselab.api.event.CorpseRemovalEvent.Cause;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.pyrostella.corpselab.internal.corpse.PlanCorpseEntity.RevivalResult;

/** 编排复活生命周期和实体树入场事务，动画计算由 CorpseRevival 负责 */
final class CorpseRevivalController {
    private final PlanCorpseEntity corpse;
    private CorpseRevival attempt;
    private boolean starting;
    private boolean finishing;

    CorpseRevivalController(PlanCorpseEntity corpse) {
        this.corpse = corpse;
    }

    boolean isActive() { return this.attempt != null; }
    boolean allowsGrab() { return !this.starting && !isActive(); }
    int basePartIndex() { return isActive() ? this.attempt.base() : this.corpse.basePartIndex(); }
    boolean regrowing(int index) { return isActive() && this.attempt.regrowing(index); }
    float scale(int index) { return isActive() ? this.attempt.scale(index) : 1; }

    Optional<CorpseView.Revival> snapshot() {
        return isActive() ? Optional.of(new CorpseView.Revival(this.attempt.elapsed(), this.attempt.duration()))
                : Optional.empty();
    }

    RevivalResult begin(RevivalOptions options) {
        if (this.starting || this.finishing) return RevivalResult.ALREADY_REVIVING;
        RevivalResult ready = readiness();
        if (ready != RevivalResult.STARTED) return ready;
        this.starting = true;
        try {
            UUID id = UUID.randomUUID();
            var pre = new CorpseReviveEvent.Pre(this.corpse, id, options);
            if (MinecraftForge.EVENT_BUS.post(pre)) return RevivalResult.INVALID_ENTITY;
            // Pre 回调可能重新加载来源数据，不能复用回调前反序列化的候选。
            ready = readiness();
            if (ready != RevivalResult.STARTED) return ready;
            options = pre.getOptions();
            int base = this.corpse.basePartIndex();
            if (options.basePart() != null) {
                base = -1;
                for (var box : this.corpse.plan().boxes()) {
                    if (box.path().equals(options.basePart())) base = box.index();
                }
                if (base < 0) return RevivalResult.INVALID_ENTITY;
            }
            var target = options.target().sample(Corpses.find(this.corpse).orElseThrow(), this.corpse.plan());
            if (this.corpse.isRemoved() || target == null || !target.matches(this.corpse.plan())) {
                return RevivalResult.INVALID_ENTITY;
            }
            var active = new CorpseRevival(this.corpse.ragdoll(), base, this.corpse.getYRot(),
                    options.durationTicks(), target, id);
            this.corpse.releaseGrabForRevival();
            if (this.corpse.isRemoved()) return RevivalResult.INVALID_ENTITY;
            this.attempt = active;
            this.corpse.clearLimbProxies();
            this.corpse.setReviving(true);
            active.apply(this.corpse.ragdoll());
            publishPose();
            MinecraftForge.EVENT_BUS.post(new CorpseReviveEvent.Start(this.corpse, id, active.duration()));
            return RevivalResult.STARTED;
        } finally {
            this.starting = false;
        }
    }

    /** 当前条件检查不会保存候选实体，也不会启动动画 */
    RevivalResult readiness() {
        if (this.corpse.isRemoved() || !(this.corpse.level instanceof ServerLevel level)
                || this.corpse.ragdoll() == null || this.corpse.plan().boxCount() == 0) {
            return RevivalResult.INVALID_ENTITY;
        }
        if (isActive()) return RevivalResult.ALREADY_REVIVING;
        CompoundTag source = this.corpse.sourceEntityData();
        if (source.isEmpty() || !source.hasUUID("UUID")) return RevivalResult.MISSING_DATA;
        Entity candidate = restoreCandidate(level, source);
        if (!(candidate instanceof LivingEntity) || candidate instanceof Player) return RevivalResult.INVALID_ENTITY;
        return hasUuidConflict(level, candidate) ? RevivalResult.UUID_IN_USE : RevivalResult.STARTED;
    }

    /** 返回 true 表示本 tick 由复活控制器接管，普通物理和年龄保持暂停 */
    boolean tick() {
        CorpseRevival active = this.attempt;
        if (active == null) return false;
        boolean done = active.advance();
        active.apply(this.corpse.ragdoll());
        publishPose();
        MinecraftForge.EVENT_BUS.post(new CorpseReviveEvent.Tick(this.corpse, active.id(),
                active.elapsed(), active.duration()));
        if (this.attempt == active && !this.corpse.isRemoved() && done) finish(active);
        return true;
    }

    boolean stop() {
        return !this.finishing && interrupt(CorpseReviveEvent.Interruption.STOPPED);
    }

    boolean interrupt(CorpseReviveEvent.Interruption reason) {
        CorpseRevival active = this.attempt;
        if (active == null) return false;
        this.attempt = null;
        this.corpse.setReviving(false);
        if (!this.corpse.isRemoved()) this.corpse.sendCurrentPose();
        MinecraftForge.EVENT_BUS.post(new CorpseReviveEvent.Interrupted(this.corpse, active.id(), reason));
        return true;
    }

    private void publishPose() {
        this.corpse.syncAnchorToRagdoll();
        this.corpse.sendCurrentPose();
    }

    private Entity restoreCandidate(ServerLevel level, CompoundTag source) {
        try {
            return EntityType.loadEntityRecursive(source, level, entity -> {
                if (entity instanceof LivingEntity living) {
                    if (living.getHealth() <= 0) living.setHealth(living.getMaxHealth());
                    living.deathTime = 0;
                    living.hurtTime = 0;
                    living.invulnerableTime = 0;
                }
                entity.setDeltaMovement(Vec3.ZERO);
                return entity;
            });
        } catch (RuntimeException failure) {
            CorpseLab.LOGGER.error("原生物数据无法还原", failure);
            return null;
        }
    }

    private boolean hasUuidConflict(ServerLevel level, Entity candidate) {
        return candidate.getSelfAndPassengers().anyMatch(member -> {
            for (ServerLevel dimension : level.getServer().getAllLevels()) {
                Entity existing = dimension.getEntity(member.getUUID());
                if (existing != null && !existing.isRemoved() && (!(existing instanceof LivingEntity living)
                        || living.isAlive() || existing.getType() != member.getType())) return true;
            }
            return false;
        });
    }

    private void finish(CorpseRevival active) {
        ServerLevel level = (ServerLevel) this.corpse.level;
        Entity candidate = restoreCandidate(level, this.corpse.sourceEntityData());
        if (!(candidate instanceof LivingEntity living)) {
            interrupt(CorpseReviveEvent.Interruption.INVALID_DATA);
            return;
        }
        if (hasUuidConflict(level, candidate)) {
            interrupt(CorpseReviveEvent.Interruption.UUID_CONFLICT);
            return;
        }
        List<Entity> members = candidate.getSelfAndPassengers().toList();
        Vec3 position = active.origin();
        for (Entity member : members) {
            member.moveTo(position.x, position.y, position.z, member.getYRot(), member.getXRot());
        }
        candidate.moveTo(position.x, position.y, position.z, active.yaw(), 0);
        living.yBodyRot = active.yaw();
        living.setYHeadRot(active.yaw());
        boolean committed = false;
        this.finishing = true;
        try {
            for (Entity member : members) {
                // 仅让出已经死亡的同类型原实体 UUID，绝不覆盖活实体。
                for (ServerLevel dimension : level.getServer().getAllLevels()) {
                    Entity old = dimension.getEntity(member.getUUID());
                    if (old instanceof LivingEntity dead && !dead.isAlive() && old.getType() == member.getType()) {
                        old.discard();
                    }
                }
                if (!level.addFreshEntity(member) || member.isRemoved()) return;
                if (this.attempt != active || this.corpse.isRemoved()) return;
            }
            // 后续成员入场回调可能移除先前成员，提交前再次核对整棵实体树。
            for (Entity member : members) {
                if (member.isRemoved() || level.getEntity(member.getUUID()) != member) return;
            }
            committed = true;
        } finally {
            this.finishing = false;
            if (!committed) {
                for (Entity member : members) member.discard();
                if (this.attempt == active) interrupt(CorpseReviveEvent.Interruption.SPAWN_FAILED);
            }
        }
        this.attempt = null;
        this.corpse.setReviving(false);
        CorpseLifecycle.discard(this.corpse, Cause.REVIVED);
        MinecraftForge.EVENT_BUS.post(new CorpseReviveEvent.Complete(this.corpse, active.id(), living));
    }

    void reset() {
        this.attempt = null;
        this.corpse.setReviving(false);
    }

    void load(CompoundTag tag) {
        if (!tag.contains("Revival")) return;
        this.attempt = CorpseRevival.load(tag.getCompound("Revival"), this.corpse.plan().boxCount());
        this.corpse.setReviving(true);
        this.attempt.apply(this.corpse.ragdoll());
        this.corpse.syncAnchorToRagdoll();
    }

    void save(CompoundTag tag) {
        if (this.attempt != null) tag.put("Revival", this.attempt.save());
    }
}
