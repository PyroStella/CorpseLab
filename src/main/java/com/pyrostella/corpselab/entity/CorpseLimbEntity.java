package com.pyrostella.corpselab.entity;

import com.pyrostella.corpselab.physics.RigidBody;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** 肢体的环境交互入口，运动与旋转始终由布娃娃求解器决定 */
public final class CorpseLimbEntity extends Entity {
    private Entity owner;
    private RigidBody body;
    private Vec3 publishedVelocity = Vec3.ZERO;

    public CorpseLimbEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
    }

    public void attach(Entity owner, RigidBody body) {
        this.owner = owner;
        this.body = body;
        this.publishPose();
    }

    /** 供外部交互逻辑追溯所属尸体，肢体本身不伪装成活体 */
    public Entity corpse() {
        return this.owner;
    }

    @Override
    public boolean isPushable() {
        return true;
    }

    /** 原版速度为格每 tick，求解器速度为格每秒 */
    public boolean consumeMotion() {
        Vec3 velocity = this.getDeltaMovement();
        boolean changed = velocity.distanceToSqr(this.publishedVelocity) > 1.0E-16D;
        if (changed && Double.isFinite(velocity.x) && Double.isFinite(velocity.y)
                && Double.isFinite(velocity.z)) {
            this.body.linVel.set(velocity.x * 20.0D, velocity.y * 20.0D, velocity.z * 20.0D);
            return true;
        }
        return false;
    }

    public void publishPose() {
        this.setPos(this.body.pos.x, this.body.pos.y, this.body.pos.z);
        this.setBoundingBox(PlanCorpseEntity.blockBounds(this.body.pos, this.body.rot, this.body.halfExtents));
        this.publishedVelocity = new Vec3(this.body.linVel.x / 20.0D,
                this.body.linVel.y / 20.0D, this.body.linVel.z / 20.0D);
        this.setDeltaMovement(this.publishedVelocity);
    }

    @Override
    public void tick() {
        if (!this.level().isClientSide && (this.owner == null || this.owner.isRemoved())) {
            this.discard();
            return;
        }
        // 包含原版与 Forge 流体推力入口，不调用 move，避免重复求解地形碰撞
        super.tick();
        if (!this.level().isClientSide) {
            this.checkInsideBlocks();
            // 基础 Entity 没有活体的流体阻尼，显式补上水中和岩浆中的速度衰减
            if (this.isInWaterOrBubble()) {
                this.setDeltaMovement(this.getDeltaMovement().scale(0.8D));
            } else if (this.isInLava()) {
                this.setDeltaMovement(this.getDeltaMovement().scale(0.5D));
            }
            if (this.stuckSpeedMultiplier.lengthSqr() > 0.0D) {
                this.setDeltaMovement(this.getDeltaMovement().multiply(this.stuckSpeedMultiplier));
                this.stuckSpeedMultiplier = Vec3.ZERO;
            }
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // 保留实体受击入口，尸体不因伤害重新死亡或重复掉落
        this.markHurt();
        return true;
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
