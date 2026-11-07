package com.pyrostella.corpselab.internal.corpse;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.internal.environment.LimbEffectSession;
import com.pyrostella.corpselab.physics.RigidBody;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 肢体的环境交互入口，运动与旋转始终由布娃娃求解器决定 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID)
public final class CorpseLimbEntity extends Entity {
    private Entity owner;
    private RigidBody body;
    private Vec3 publishedVelocity = Vec3.ZERO;
    private LimbEffectSession effects;
    private boolean environmentChanged;

    public CorpseLimbEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
    }

    public void attach(Entity owner, RigidBody body) {
        this.owner = owner;
        this.body = body;
        this.effects = new LimbEffectSession(this, body);
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
            this.environmentChanged |= this.effects.tick();
            if (this.stuckSpeedMultiplier.lengthSqr() > 0.0D) {
                this.setDeltaMovement(this.getDeltaMovement().multiply(this.stuckSpeedMultiplier));
                this.stuckSpeedMultiplier = Vec3.ZERO;
            }
        }
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (CorpseInteraction.isPlayerDamage(source)
                || this.level().isClientSide || this.body == null || !(amount > 0)
                || this.isInvulnerableTo(source)) {
            return false;
        }
        this.effects.hurt(source, amount);
        this.markHurt();
        return true;
    }

    /** 原版无来源实体的爆炸伤害不保存爆心，在爆炸事件中补充方向和遮挡信息 */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        for (Entity entity : event.getAffectedEntities()) {
            if (entity instanceof CorpseLimbEntity limb && limb.effects != null) {
                limb.effects.explosion(event.getExplosion());
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
