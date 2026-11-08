package com.pyrostella.corpselab.internal.corpse;

import com.pyrostella.corpselab.client.animation.PoseBuffer;

import java.util.*;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.internal.registry.CorpseLabRegistry;
import com.pyrostella.corpselab.internal.network.CorpseLabNetwork;
import com.pyrostella.corpselab.internal.network.PlanPosePacket;
import com.pyrostella.corpselab.api.model.BodyPlan;
import com.pyrostella.corpselab.internal.network.BodyPlanCodec;
import com.pyrostella.corpselab.internal.config.PlanConfig;
import com.pyrostella.corpselab.internal.skeleton.PlanPose;
import com.pyrostella.corpselab.internal.skeleton.PlanRagdoll;
import com.pyrostella.corpselab.physics.RigidBody;
import com.pyrostella.corpselab.api.CorpseView;
import com.pyrostella.corpselab.api.event.CorpseReviveEvent;
import com.pyrostella.corpselab.api.event.CorpseGrabEvent;
import com.pyrostella.corpselab.api.event.CorpseRemovalEvent.Cause;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;

/**
 * 用通用骨架驱动的尸体实体
 *
 * <p>服务端持有 {@link PlanRagdoll} 跑物理，每个 tick 把各刚体的位姿发给跟踪者
 * 客户端根据同步姿态绘制已绑定的模型部件。</p>
 */
public class PlanCorpseEntity extends Entity implements GrabbableCorpse {

    private static final BodyPlan EMPTY = new BodyPlan(List.of(), List.of());
    private static final EntityDataAccessor<Boolean> REVIVING = SynchedEntityData.defineId(PlanCorpseEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> CORPSE_SIZE = SynchedEntityData.defineId(PlanCorpseEntity.class, EntityDataSerializers.FLOAT);

    /** 客户端保存最近两帧姿态用于插值 */
    @Nullable
    private PoseBuffer poses;

    /** 服务端：物理骨架 */
    @Nullable
    private PlanRagdoll ragdoll;

    private BodyPlan plan = EMPTY;
    private int largestPartIndex = -1;

    /** 死亡生物的类型，客户端据此找模型与贴图 */
    private ResourceLocation sourceType = new ResourceLocation("minecraft", "pig");
    private CompoundTag appearance = new CompoundTag();
    private CompoundTag sourceEntityData = new CompoundTag();
    private int appearanceVersion;

    /** 已存在的 tick 数，用于过期清理 */
    private int age;
    private final CorpseRevivalController revival = new CorpseRevivalController(this);

    /** 仅服务端持有的抓取状态 */
    private final CorpseGrabState grab = new CorpseGrabState(this);
    private final CorpseLimbBridge limbs = new CorpseLimbBridge(this);

    public PlanCorpseEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    /** 从原生物继承位置、朝向、三轴速度、死亡姿态及完整原始数据 */
    public static PlanCorpseEntity spawn(ServerLevel level, BodyPlan plan, LivingEntity source) {
        return spawn(level, plan, EntityType.getKey(source.getType()), source.position(), source.yBodyRot,
                CorpseSourceData.velocity(source), source);
    }

    /** 显式指定初速度，速度单位为格每秒 */
    public static PlanCorpseEntity spawn(ServerLevel level, BodyPlan plan, ResourceLocation sourceType,
                                         Vec3 pos, float yaw, Vec3 impulse,
                                         @Nullable LivingEntity source) {
        return CorpseCreation.createMob(level, plan, sourceType, pos, yaw, impulse, source);
    }

    /** 创建未入场的完整实体，事务和事件由创建服务负责 */
    static PlanCorpseEntity prepare(ServerLevel level, BodyPlan plan, ResourceLocation sourceType,
                                    Vec3 pos, float yaw, Vec3 impulse, @Nullable LivingEntity source) {
        PlanCorpseEntity corpse = CorpseLabRegistry.PLAN_CORPSE.get().create(level);
        if (corpse == null) {
            return null;
        }

        corpse.setPlan(plan);
        corpse.sourceType = sourceType;
        if (source != null) {
            corpse.sourceEntityData = CorpseSourceData.capture(source);
            corpse.setAppearance(CorpseAppearance.fromSnapshot(corpse.sourceEntityData));
        }
        corpse.setCorpseSize(source == null ? sourceWidth(sourceType) : source.getBbWidth());
        corpse.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);

        // 继承死亡那一刻的姿势：奔跑、张望、下蹲都被记住，尸体不是凭空站直的
        PlanPose.Frame[] pose = source == null ? null : PlanPose.frames(plan, source);
        corpse.ragdoll = new PlanRagdoll(plan, pos.x, pos.y, pos.z,
                Math.toRadians(yaw), impulse, pose);
        corpse.ragdoll.setCollider(new LevelCollider(level));
        corpse.syncAnchorToRagdoll();

        return corpse;
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level.isClientSide) {
            if (this.poses != null) {
                this.poses.onClientTick();
                this.poses.sample(1.0F);
                this.updateClientBounds();
            }
            return;
        }

        if (this.revival.tick()) {
            return;
        }
        this.age++;
        if (this.age > PlanConfig.maxAgeTicks()) {
            CorpseLifecycle.discard(this, Cause.EXPIRED);
            return;
        }

        if (this.ragdoll == null) {
            return;
        }

        boolean environmentChanged = this.limbs.consume(this.ragdoll.bodies());

        this.grab.apply(this.level, this.ragdoll.bodies());

        // 远处冻结：不跑物理也不发姿态，玩家走近会接着模拟，省 CPU
        if (!environmentChanged && !this.grab.isGrabbed() && !this.hasNearbyPlayer()) {
            return;
        }

        this.ragdoll.step(0.05D);
        this.syncAnchorToRagdoll();
        this.limbs.publish();

        // 已经躺稳的尸体没必要每 tick 全量发姿态，降频到 1 秒一次
        boolean settled = this.ragdoll.isSettled();
        if (!settled || this.tickCount % 20 == 0) {
            CorpseLabNetwork.CHANNEL.send(
                    PacketDistributor.TRACKING_ENTITY.with(() -> this),
                    PlanPosePacket.of(this));
        }
    }

    /** 是否有玩家在活动半径内 */
    private boolean hasNearbyPlayer() {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return false;
        }
        double radius = PlanConfig.activeRadius();
        double radiusSqr = radius * radius;
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceToSqr(this) <= radiusSqr) {
                return true;
            }
        }
        return false;
    }

    /** 供后续恢复原生物使用，调用方修改副本不会影响保存的记录 */
    public CompoundTag sourceEntityData() {
        return this.sourceEntityData.copy();
    }
    /** 网络关联只读取来源标识，不复制完整原实体数据 */
    public Optional<UUID> sourceEntityId() {
        return this.sourceEntityData.hasUUID("UUID") ? Optional.of(this.sourceEntityData.getUUID("UUID")) : Optional.empty();
    }

    /** 存在时间，复活时暂停，最老优先用于区域淘汰 */
    public int age() {
        return this.age;
    }

    @Override
    public void setLevelCallback(EntityInLevelCallback callback) {
        // NULL 只是管理器解除关联，不代表发生了新的移除
        super.setLevelCallback(callback == EntityInLevelCallback.NULL ? callback
                : new CorpseLevelCallback(this, callback, this::finishRemoval));
    }

    private void finishRemoval(RemovalReason reason) {
        this.grab.release(reason.shouldDestroy() ? CorpseGrabEvent.ReleaseReason.REMOVED : CorpseGrabEvent.ReleaseReason.UNLOADED);
        if (reason.shouldDestroy()) this.revival.interrupt(CorpseReviveEvent.Interruption.REMOVED);
        this.limbs.clear();
        CorpseLifecycle.onRemoved(this, reason);
    }

    @Override
    public boolean ignoreExplosion() {
        // 爆炸由每个肢体接收，父实体不重复受力
        return true;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return !this.revival.isActive() && !this.level.isClientSide && this.limbs.hurt(source, amount);
    }

    /** 服务端取物理骨架 */
    @Nullable
    public PlanRagdoll ragdoll() {
        return this.ragdoll;
    }

    public BodyPlan plan() {
        return this.plan;
    }

    public ResourceLocation sourceType() {
        return this.sourceType;
    }

    public CompoundTag appearance() {
        return this.appearance.copy();
    }

    public int appearanceVersion() {
        return this.appearanceVersion;
    }

    public void setAppearance(CompoundTag appearance) {
        this.appearance = appearance.copy();
        this.appearanceVersion++;
    }

    @Override
    public boolean tryGrab(net.minecraft.world.entity.player.Player grabber) {
        return this.revival.allowsGrab() && this.ragdoll != null && this.grab.tryGrab(grabber, this.ragdoll.bodies());
    }

    public Optional<CorpseView.Grab> grabInfo() {
        return this.grab.snapshot();
    }

    @Override
    public void releaseGrab() {
        this.grab.release();
    }

    @Override
    public boolean isGrabbedBy(net.minecraft.world.entity.player.Player player) {
        return this.grab.isGrabbedBy(player);
    }

    public void setSourceType(ResourceLocation sourceType) {
        this.sourceType = sourceType;
    }

    public void setPlan(BodyPlan plan) {
        this.plan = plan;
        this.largestPartIndex = plan.largestPartIndex();
        if (this.level.isClientSide
                && (this.poses == null || plan.boxCount() != this.poses.partCount())) {
            this.poses = new PoseBuffer(plan.boxCount());
        }
    }

    /** 与实体碰撞箱采用相同的最大体积部件，作为复活模型的基准 */
    public int basePartIndex() {
        return this.largestPartIndex;
    }

    public int revivalBasePartIndex() { return this.revival.basePartIndex(); }

    public enum RevivalResult { STARTED, ALREADY_REVIVING, MISSING_DATA, UUID_IN_USE, INVALID_ENTITY }
    public boolean isReviving() { return this.entityData.get(REVIVING); }
    public boolean limbRegrowing(int index) { return this.revival.regrowing(index); }
    public float limbScale(int index) {
        if (this.level.isClientSide) return this.poses == null ? 1 : this.poses.scale(index);
        return this.revival.scale(index);
    }
    public RevivalResult beginRevival() { return beginRevival(40); }

    /** 仅服务端调用，物品、技能等触发器可复用此入口 */
    public RevivalResult beginRevival(int durationTicks) {
        if (durationTicks < 1) throw new IllegalArgumentException("复活动画时长必须为正数");
        return beginRevival(com.pyrostella.corpselab.api.RevivalOptions.defaults().duration(durationTicks));
    }

    public RevivalResult beginRevival(com.pyrostella.corpselab.api.RevivalOptions options) {
        return this.revival.begin(options);
    }

    /** STARTED 在此表示允许启动，查询不会修改尸体状态 */
    public RevivalResult revivalReadiness() { return this.revival.readiness(); }

    public Optional<CorpseView.Revival> revivalInfo() { return this.revival.snapshot(); }

    /** 主动停止动画并保留当前姿态与原始数据，下一 tick 恢复普通物理 */
    public boolean stopRevival() { return this.revival.stop(); }

    void setReviving(boolean active) { this.entityData.set(REVIVING, active); }

    void releaseGrabForRevival() { this.grab.release(CorpseGrabEvent.ReleaseReason.REVIVAL); }

    void clearLimbProxies() { this.limbs.clear(); }

    void sendCurrentPose() {
        CorpseLabNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> this), PlanPosePacket.of(this));
    }

    /** 客户端取姿态缓冲，还没收到数据时返回 null */
    @Nullable
    public PoseBuffer poses() {
        return this.poses;
    }

    /** 收到一帧姿态，同时把客户端实体锚点对齐到服务端质心 */
    public void acceptPose(double anchorX, double anchorY, double anchorZ,
                           Vec3[] offsets, Quaternionf[] rotations) {
        float[] scales = new float[offsets.length]; java.util.Arrays.fill(scales,1);
        acceptPose(anchorX,anchorY,anchorZ,offsets,rotations,scales);
    }

    public void acceptPose(double anchorX,double anchorY,double anchorZ,Vec3[] offsets,Quaternionf[] rotations,float[] scales) {
        acceptPose(anchorX,anchorY,anchorZ,offsets,rotations,scales,new boolean[scales.length]);
    }

    public void acceptPose(double anchorX,double anchorY,double anchorZ,Vec3[] offsets,Quaternionf[] rotations,float[] scales,boolean[] regrowing) {
        this.setPos(anchorX, anchorY, anchorZ);
        this.xOld = anchorX;
        this.yOld = anchorY;
        this.zOld = anchorZ;

        Vec3[] positions = new Vec3[offsets.length];
        for (int i = 0; i < offsets.length; i++) {
            positions[i] = offsets[i].add(anchorX, anchorY, anchorZ);
        }
        if (this.poses == null || this.poses.partCount() != positions.length) {
            this.poses = new PoseBuffer(positions.length);
        }
        this.poses.push(positions, rotations,scales,regrowing);
        this.poses.sample(1.0F);
        this.updateClientBounds();
    }

    /** 让用于追踪、碰撞箱和服务端查询的实体位置跟随物理尸体 */
    void syncAnchorToRagdoll() {
        if (this.ragdoll == null || this.ragdoll.bodies().isEmpty()) {
            return;
        }
        Vector3d centre = this.ragdoll.centreOfMass(new Vector3d());
        this.setPos(centre.x, centre.y, centre.z);
        // 质心不动时最大块仍可能移动或旋转，不能只依赖实体位移刷新边界
        this.setBoundingBox(this.makeBoundingBox());
    }

    /** 两端均只跟随体积最大的块，客户端使用同步后的姿态 */
    @Override
    protected AABB makeBoundingBox() {
        int index = this.plan == null ? -1 : this.largestPartIndex;
        if (index < 0) return this.grabSearchBounds();
        if (this.ragdoll != null) {
            RigidBody body = this.ragdoll.body(index);
            return blockBounds(body.pos, body.rot, body.halfExtents);
        }
        if (this.level.isClientSide && this.poses != null && this.poses.hasPose()
                && this.poses.partCount() == this.plan.boxCount()) {
            BodyPlan.Box box = this.plan.boxes().get(index);
            Vec3 pos = this.poses.position(index);
            Quaternionf rotation = this.poses.rotation(index, new Quaternionf());
            return blockBounds(new Vector3d(pos.x, pos.y, pos.z), new Quaterniond(rotation),
                    new Vector3d(box.hx(), box.hy(), box.hz()));
        }
        return this.grabSearchBounds();
    }

    /** 渲染采样后刷新，确保客户端盒与这一帧的最大块模型一致 */
    public void updateClientBounds() {
        if (this.level.isClientSide) {
            this.setBoundingBox(this.makeBoundingBox());
        }
    }

    /** 原版实体盒只能轴对齐，两端共用旋转盒的精确外接范围计算 */
    static AABB blockBounds(Vector3d pos, Quaterniondc rotation, Vector3d half) {
        Vector3d x = rotation.transform(new Vector3d(half.x, 0, 0));
        Vector3d y = rotation.transform(new Vector3d(0, half.y, 0));
        Vector3d z = rotation.transform(new Vector3d(0, 0, half.z));
        double hx = Math.abs(x.x) + Math.abs(y.x) + Math.abs(z.x);
        double hy = Math.abs(x.y) + Math.abs(y.y) + Math.abs(z.y);
        double hz = Math.abs(x.z) + Math.abs(y.z) + Math.abs(z.z);
        return new AABB(pos.x - hx, pos.y - hy, pos.z - hz,
                pos.x + hx, pos.y + hy, pos.z + hz);
    }

    /** 抓取粗筛保留原来的质心和尺寸，不受服务端逻辑盒变更影响 */
    public AABB grabSearchBounds() {
        double half = this.entityData.get(CORPSE_SIZE) * 0.5D;
        return new AABB(this.getX() - half, this.getY() - half, this.getZ() - half,
                this.getX() + half, this.getY() + half, this.getZ() + half);
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        float size = this.entityData.get(CORPSE_SIZE);
        return EntityDimensions.scalable(size, size);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (CORPSE_SIZE.equals(key)) {
            this.refreshDimensions();
            this.setBoundingBox(this.makeBoundingBox());
        }
    }

    private void setCorpseSize(float size) {
        this.entityData.set(CORPSE_SIZE, Float.isFinite(size) && size > 0.0F ? size : 0.8F);
    }

    /** 没有来源实例的显式创建使用实体类型的默认宽度 */
    private static float sourceWidth(ResourceLocation sourceType) {
        return EntityType.byString(sourceType.toString()).map(EntityType::getWidth).orElse(0.8F);
    }

    // ---------- 存档 ----------

    @Override
    protected void defineSynchedData() {
        this.entityData.define(CORPSE_SIZE, 0.8F);
        this.entityData.define(REVIVING,false);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.revival.reset();
        this.ragdoll = null;
        this.sourceEntityData = tag.getCompound("SourceEntityData").copy();
        this.setAppearance(tag.getCompound("Appearance"));
        this.age = tag.getInt("Age");
        try {
            this.sourceType = new ResourceLocation(tag.getString("SourceType"));
            float size = tag.getFloat("CorpseSize");
            if (!Float.isFinite(size) || size <= 0 || !tag.contains("Plan") || !tag.contains("PhysicsState")) {
                throw new IllegalArgumentException("尸体存档缺少有效尺寸、骨架或物理状态");
            }
            this.setCorpseSize(size);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(tag.getByteArray("Plan")));
            try {
                this.setPlan(BodyPlanCodec.read(buffer));
                if (this.plan.boxCount() == 0 || !this.plan.isValid() || buffer.readableBytes() != 0) {
                    throw new IllegalArgumentException("尸体存档骨架无效");
                }
            } finally {
                buffer.release();
            }
            if (!this.level.isClientSide) {
                CompoundTag state = tag.getCompound("PhysicsState");
                this.ragdoll = new PlanRagdoll(this.plan, this.getX(), this.getY(), this.getZ(),
                        Math.toRadians(this.getYRot()), null, null, state.getBoolean("Articulated"));
                this.ragdoll.loadState(state);
                this.ragdoll.setCollider(new LevelCollider(this.level));
                this.syncAnchorToRagdoll();
                this.revival.load(tag);
            }
        } catch (RuntimeException failure) {
            CorpseLab.LOGGER.error("尸体存档损坏，移除无法恢复的尸体", failure);
            this.revival.reset();
            this.ragdoll = null;
            this.discard();
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (this.ragdoll != null) tag.put("PhysicsState",this.ragdoll.saveState());
        this.revival.save(tag);
        tag.put("SourceEntityData", this.sourceEntityData.copy());
        tag.put("Appearance", this.appearance.copy());
        tag.putInt("Age", this.age);
        tag.putString("SourceType", this.sourceType.toString());
        tag.putFloat("CorpseSize", this.entityData.get(CORPSE_SIZE));
        if (this.plan.boxCount() > 0) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                BodyPlanCodec.write(buf, this.plan);
                tag.putByteArray("Plan", ByteBufUtil.getBytes(buf));
            } finally {
                buf.release();
            }
        }
    }

    /** 供包类使用：按骨骼下标取刚体 */
    public List<RigidBody> rigidBodies() {
        return this.ragdoll == null ? List.of() : this.ragdoll.bodies();
    }

    /** 1.19.2 的 Entity 把生成包声明成抽象方法，必须自己给 */
    @Override
    public Packet<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
