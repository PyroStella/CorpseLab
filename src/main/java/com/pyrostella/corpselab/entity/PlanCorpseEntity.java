package com.pyrostella.corpselab.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.CorpseLabRegistry;
import com.pyrostella.corpselab.net.CorpseLabNetwork;
import com.pyrostella.corpselab.net.PlanPosePacket;
import com.pyrostella.corpselab.plan.BodyPlan;
import com.pyrostella.corpselab.plan.BodyPlanCodec;
import com.pyrostella.corpselab.plan.PlanConfig;
import com.pyrostella.corpselab.plan.PlanPose;
import com.pyrostella.corpselab.plan.PlanRagdoll;
import com.pyrostella.corpselab.physics.RigidBody;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

/**
 * 用通用骨架驱动的尸体实体
 *
 * <p>服务端持有 {@link PlanRagdoll} 跑物理，每个 tick 把各刚体的位姿发给跟踪者
 * 客户端只负责把这些位姿画出来（当前是线框，后续换成模型部件）。</p>
 */
public class PlanCorpseEntity extends Entity implements GrabbableCorpse {

    private static final BodyPlan EMPTY = new BodyPlan(List.of(), List.of());

    /** 客户端保存最近两帧姿态用于插值 */
    @Nullable
    private PoseBuffer poses;

    /** 服务端：物理骨架 */
    @Nullable
    private PlanRagdoll ragdoll;

    private BodyPlan plan = EMPTY;

    /** 死亡生物的类型，客户端据此找模型与贴图 */
    private ResourceLocation sourceType = new ResourceLocation("minecraft", "pig");

    /** 已存在的 tick 数，用于过期清理 */
    private int age;

    /** 仅服务端持有的抓取状态 */
    private final CorpseGrabState grab = new CorpseGrabState();

    public PlanCorpseEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    /**
     * 服务端生成一具尸体
     *
     * @param level   服务端世界
     * @param plan    骨架
     * @param pos     死亡位置（脚底）
     * @param yaw     死亡朝向
     * @param impulse 死亡时的动量
     * @param source  死者；用来继承死亡那一刻的骨骼姿势，可以为 null
     */
    public static PlanCorpseEntity spawn(ServerLevel level, BodyPlan plan, ResourceLocation sourceType,
                                         Vec3 pos, float yaw, Vec3 impulse,
                                         @Nullable LivingEntity source) {
        PlanCorpseEntity corpse = CorpseLabRegistry.PLAN_CORPSE.get().create(level);
        if (corpse == null) {
            return null;
        }

        corpse.plan = plan;
        corpse.sourceType = sourceType;
        corpse.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);

        // 同区域尸体数量上限：超出时删最老的，避免一地尸体拖垮服务端
        List<PlanCorpseEntity> existing = level.getEntitiesOfClass(PlanCorpseEntity.class,
                new AABB(pos, pos).inflate(96.0D));
        int max = PlanConfig.maxPerArea();
        if (existing.size() >= max) {
            existing.sort(Comparator.comparingInt(PlanCorpseEntity::age).reversed());
            for (int i = 0; i < existing.size() - max + 1; i++) {
                existing.get(i).discard();
            }
        }
        // 继承死亡那一刻的姿势：奔跑、张望、下蹲都被记住，尸体不是凭空站直的
        PlanPose.Frame[] pose = source == null ? null : PlanPose.frames(plan, source);
        corpse.ragdoll = new PlanRagdoll(plan, pos.x, pos.y, pos.z,
                Math.toRadians(yaw), impulse, pose);
        corpse.ragdoll.setCollider(new LevelCollider(level));
        corpse.syncAnchorToRagdoll();

        level.addFreshEntity(corpse);
        return corpse;
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide) {
            if (this.poses != null) {
                this.poses.onClientTick();
            }
            return;
        }

        this.age++;
        if (this.age > PlanConfig.maxAgeTicks()) {
            this.discard();
            return;
        }

        if (this.ragdoll == null) {
            return;
        }

        this.grab.apply(this.level(), this.ragdoll.bodies());

        // 远处冻结：不跑物理也不发姿态，玩家走近会接着模拟，省 CPU
        if (!this.grab.isGrabbed() && !this.hasNearbyPlayer()) {
            return;
        }

        this.ragdoll.step(0.05D);
        this.syncAnchorToRagdoll();

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
        if (!(this.level() instanceof ServerLevel serverLevel)) {
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

    /** 存在了多少 tick，用于过期清理和最老优先淘汰 */
    public int age() {
        return this.age;
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

    @Override
    public boolean tryGrab(net.minecraft.world.entity.player.Player grabber) {
        return this.ragdoll != null && this.grab.tryGrab(grabber, this.ragdoll.bodies());
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
        if (this.level().isClientSide
                && (this.poses == null || plan.boxCount() != this.poses.partCount())) {
            this.poses = new PoseBuffer(plan.boxCount());
        }
    }

    /** 客户端取姿态缓冲，还没收到数据时返回 null */
    @Nullable
    public PoseBuffer poses() {
        return this.poses;
    }

    /** 收到一帧姿态，同时把客户端实体锚点对齐到服务端质心 */
    public void acceptPose(double anchorX, double anchorY, double anchorZ,
                           Vec3[] offsets, Quaternionf[] rotations) {
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
        this.poses.push(positions, rotations);
    }

    /** 让用于追踪、碰撞箱和服务端查询的实体位置跟随物理尸体 */
    private void syncAnchorToRagdoll() {
        if (this.ragdoll == null || this.ragdoll.bodies().isEmpty()) {
            return;
        }
        Vector3d centre = this.ragdoll.centreOfMass(new Vector3d());
        this.setPos(centre.x, centre.y, centre.z);
    }

    /** 服务端查询抓取目标时必须覆盖所有刚体，不能只覆盖质心附近 */
    @Override
    protected AABB makeBoundingBox() {
        if (this.ragdoll == null || this.ragdoll.bodies().isEmpty()) {
            return super.makeBoundingBox();
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (RigidBody body : this.ragdoll.bodies()) {
            // 旋转盒的轴对齐边界不会超过其外接球
            double radius = body.halfExtents.length();
            minX = Math.min(minX, body.pos.x - radius);
            minY = Math.min(minY, body.pos.y - radius);
            minZ = Math.min(minZ, body.pos.z - radius);
            maxX = Math.max(maxX, body.pos.x + radius);
            maxY = Math.max(maxY, body.pos.y + radius);
            maxZ = Math.max(maxZ, body.pos.z + radius);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    // ---------- 存档 ----------

    @Override
    protected void defineSynchedData() {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.age = tag.getInt("Age");
        if (tag.contains("SourceType")) {
            this.sourceType = new ResourceLocation(tag.getString("SourceType"));
        }
        if (tag.contains("Plan")) {
            try {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(tag.getByteArray("Plan")));
                this.plan = BodyPlanCodec.read(buf);
                if (!this.level().isClientSide) {
                    this.ragdoll = new PlanRagdoll(this.plan, this.getX(), this.getY(), this.getZ(),
                            Math.toRadians(this.getYRot()), null);
                    this.ragdoll.moveCentreTo(this.getX(), this.getY(), this.getZ());
                    this.ragdoll.setCollider(new LevelCollider(this.level()));
                    this.syncAnchorToRagdoll();
                }
            } catch (Exception e) {
                CorpseLab.LOGGER.error("尸体骨架存档损坏", e);
            }
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Age", this.age);
        tag.putString("SourceType", this.sourceType.toString());
        if (this.plan.boxCount() > 0) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            BodyPlanCodec.write(buf, this.plan);
            tag.putByteArray("Plan", ByteBufUtil.getBytes(buf));
        }
    }

    /** 供包类使用：按骨骼下标取刚体 */
    public List<RigidBody> rigidBodies() {
        List<RigidBody> list = new ArrayList<>();
        if (this.ragdoll != null) {
            list.addAll(this.ragdoll.bodies());
        }
        return list;
    }
}
