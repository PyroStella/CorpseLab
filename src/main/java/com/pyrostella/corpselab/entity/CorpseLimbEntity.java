package com.pyrostella.corpselab.entity;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.physics.RigidBody;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.level.Explosion;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 肢体的环境交互入口，运动与旋转始终由布娃娃求解器决定 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID)
public final class CorpseLimbEntity extends Entity {
    private Entity owner;
    private RigidBody body;
    private Vec3 publishedVelocity = Vec3.ZERO;
    private Vec3 explosionCentre;
    private long explosionTick = -1;
    private double explosionImpact;
    private long nextKnockbackTick;
    private boolean environmentChanged;

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
        boolean environment = this.environmentChanged;
        this.environmentChanged = false;
        boolean changed = velocity.distanceToSqr(this.publishedVelocity) > 1.0E-16D;
        if (changed && Double.isFinite(velocity.x) && Double.isFinite(velocity.y)
                && Double.isFinite(velocity.z)) {
            this.body.linVel.set(velocity.x * 20.0D, velocity.y * 20.0D, velocity.z * 20.0D);
            return true;
        }
        return environment;
    }

    public void publishPose() {
        this.setPos(this.body.pos.x, this.body.pos.y, this.body.pos.z);
        this.setBoundingBox(PlanCorpseEntity.blockBounds(this.body.pos, this.body.rot, this.body.halfExtents));
        this.publishedVelocity = new Vec3(this.body.linVel.x / 20.0D,
                this.body.linVel.y / 20.0D, this.body.linVel.z / 20.0D);
        this.setDeltaMovement(this.publishedVelocity);
        this.setOnGround(this.body.onGround);
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
            // 类似活体水中运动：减轻下沉加速度，避免陆地摩擦和停稳阻尼吞掉水流
            boolean water = this.isInWaterOrBubble();
            this.environmentChanged |= this.body.gravityScale != (water ? 1.0D / 16.0D : 1.0D);
            this.body.gravityScale = water ? 1.0D / 16.0D : 1.0D;
            this.body.frictionScale = water ? 0.1D : 1.0D;
            this.body.supportDamping = water ? 1.0D : 0.85D;
            if (this.stuckSpeedMultiplier.lengthSqr() > 0.0D) {
                this.setDeltaMovement(this.getDeltaMovement().multiply(this.stuckSpeedMultiplier));
                this.stuckSpeedMultiplier = Vec3.ZERO;
            }
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.body == null || !(amount > 0)
                || this.isInvulnerableTo(source)) {
            return false;
        }
        // 尸体没有生命值，但有类似活体的受伤击退；短暂冷却避免持续伤害每帧叠加
        Vec3 origin = source.getSourcePosition();
        double strength = 0.5D;
        if (source.is(DamageTypeTags.IS_EXPLOSION)
                && this.explosionTick == this.level().getGameTime()) {
            origin = this.explosionCentre;
            strength *= Math.min(1.0D, this.explosionImpact * 2.0D);
        }
        if (origin != null && strength > 0 && this.level().getGameTime() >= this.nextKnockbackTick) {
            Vec3 direction = this.position().subtract(origin).multiply(1, 0, 1).normalize();
            Vec3 velocity = this.getDeltaMovement();
            double lift = this.body.onGround ? Math.min(0.5D, velocity.y / 2.0D + strength) : velocity.y;
            this.setDeltaMovement(velocity.x / 2.0D + direction.x * strength,
                    lift, velocity.z / 2.0D + direction.z * strength);
            this.nextKnockbackTick = this.level().getGameTime() + 10;
            this.hasImpulse = true;
        }
        this.markHurt();
        return true;
    }

    /** 原版无来源实体的爆炸伤害不保存爆心，在爆炸事件中补充方向和遮挡信息 */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        Explosion explosion = event.getExplosion();
        Vec3 centre = explosion.getPosition();
        double radius = explosion.radius * 2.0D;
        if (radius <= 0) {
            return;
        }
        for (Entity entity : event.getAffectedEntities()) {
            if (entity instanceof CorpseLimbEntity limb) {
                limb.explosionCentre = centre;
                limb.explosionTick = event.getLevel().getGameTime();
                limb.explosionImpact = Math.max(0, 1.0D - limb.position().distanceTo(centre) / radius)
                        * Explosion.getSeenPercent(centre, limb);
            }
        }
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
