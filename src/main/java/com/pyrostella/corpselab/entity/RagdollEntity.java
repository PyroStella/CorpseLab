package com.pyrostella.corpselab.entity;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.api.CorpseView;
import com.pyrostella.corpselab.config.CorpseLabConfig;
import com.pyrostella.corpselab.net.RagdollPosePacket;
import com.pyrostella.corpselab.net.CorpseLabNetwork;
import com.pyrostella.corpselab.physics.RigidBody;
import com.pyrostella.corpselab.ragdoll.BodyPart;
import com.pyrostella.corpselab.ragdoll.RagdollSkeleton;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.Optional;
import java.util.UUID;

/** 六部件人形尸体共用的物理、姿态同步与拖拽基础 */
public abstract class RagdollEntity extends Entity implements GrabbableCorpse {

    public static final int PART_COUNT = BodyPart.values().length;

    private static final EntityDataAccessor<Optional<UUID>> OWNER =
            SynchedEntityData.defineId(RagdollEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    /** 仅服务端持有。客户端上为 null，客户端只绘制收到的内容 */
    private RagdollSkeleton skeleton;

    /** 客户端渲染状态：世界空间中的快照，延迟一段时间后回放 */
    private final PoseBuffer poseBuffer = new PoseBuffer(PART_COUNT);

    /** 仅服务端持有的抓取状态 */
    private final CorpseGrabState grab = new CorpseGrabState(this);
    private final CorpseLimbBridge limbs = new CorpseLimbBridge(this);

    public RagdollEntity(EntityType<? extends RagdollEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;

        // 肢体最远会被画到超出这个 entity 自身小包围盒一个方块的位置，所以
        // 如果交给视锥剔除去管，快速移动的身体就会消失一两帧：包围盒
        // 已经移出视野，而肢体明明还清楚地留在屏幕上
        this.noCulling = true;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(OWNER, Optional.empty());
    }

    public UUID getOwnerId() {
        return entityData.get(OWNER).orElse(null);
    }

    protected void setOwnerId(UUID id) {
        entityData.set(OWNER, Optional.ofNullable(id));
    }

    public RagdollSkeleton skeleton() {
        return skeleton;
    }

    @Override
    public void tick() {
        if (level().isClientSide) {
            // 这个 entity 只是追踪和剔除用的锚点，每个可见肢体的位置
            // 都来自姿态包里的世界坐标。把上一帧的位置钉住，可以避免它
            // 自己的包围盒滞后，这对剔除有意义，但对肢体画在哪里
            // 已经没有影响了
            xOld = getX();
            yOld = getY();
            zOld = getZ();

            // 在这里而不是在包到达时把最新的姿态提为当前姿态，这样参与混合的两份姿态永远来自相邻的 tick
            poseBuffer.onClientTick();
            return;
        }

        if (skeleton == null) {
            discard();
            return;
        }

        if (this.limbs.consume(this.skeleton.bodies())) {
            this.wakeForGrab();
        }
        serverTick();
        this.limbs.publish();
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        this.grab.release(reason.shouldDestroy()
                ? com.pyrostella.corpselab.api.event.CorpseGrabEvent.ReleaseReason.REMOVED
                : com.pyrostella.corpselab.api.event.CorpseGrabEvent.ReleaseReason.UNLOADED);
        this.limbs.clear();
    }

    @Override
    public boolean ignoreExplosion() {
        return true;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return !this.level().isClientSide && this.limbs.hurt(source, amount);
    }

    /** 由具体尸体类型决定服务端每 tick 的行为 */
    protected abstract void serverTick();

    /**
     * 推进模拟，并让 entity 跟着身体移动，这样原版的追踪和区块
     * 加载都不用特殊处理就仍然正确
     */
    protected void stepPhysics() {
        skeleton.tick();
        Vector3d centre = skeleton.centreOfMass(new Vector3d());
        setPos(centre.x, centre.y, centre.z);
    }

    protected void sendPose() {
        CorpseLabNetwork.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY.with(() -> this),
                RagdollPosePacket.of(this));
    }

    /** 把当前姿态发给单个玩家，用于已经不再移动的身体 */
    public void sendPoseTo(net.minecraft.server.level.ServerPlayer player) {
        if (skeleton == null) {
            return;
        }
        CorpseLabNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                RagdollPosePacket.of(this));
    }

    protected void setSkeleton(RagdollSkeleton skeleton) {
        this.skeleton = skeleton;
    }

    /**
     * 抓住一个肢体。具体是哪个部位由服务端依据抓取者自己的视线
     * 方向选出，客户端因此无法声称抓住了自己没看的东西，也无法
     * 声称抓住了地图另一头的东西
     *
     * @return 若某个肢体在可及范围内则返回 true
     */
    public boolean tryGrab(Player grabber) {
        return this.skeleton != null && this.grab.tryGrab(grabber, this.skeleton.bodies());
    }

    public Optional<CorpseView.Grab> grabInfo() {
        return this.grab.snapshot();
    }

    public void releaseGrab() {
        this.grab.release();
    }

    /** 有人正抓着这具身体时为 true */
    public boolean isGrabbed() {
        return this.grab.isGrabbed();
    }

    public boolean isGrabbedBy(Player player) {
        return this.grab.isGrabbedBy(player);
    }

    /**
     * 把被抓住的肢体拖向抓取者的手
     *
     * <p>这里调的是速度，而不是直接把位置设过去，身体在移动途中
     * 因此仍会与世界碰撞：被拖进墙里的尸体会卡在墙上而不是穿
     * 过去，于是落在手的后面，抓握随即断开
     */
    protected void applyGrab() {
        if (this.skeleton != null) {
            this.grab.apply(level(), this.skeleton.bodies());
        }
    }

    /** 把整套装配体抛出去，并做限幅，确保没有部件飞出世界 */
    public void launch(Vec3 velocity) {
        if (skeleton == null) {
            return;
        }
        double cap = CorpseLabConfig.SERVER.maxLaunchSpeed.get();
        Vec3 clamped = velocity.length() > cap ? velocity.normalize().scale(cap) : velocity;
        skeleton.launch(new Vector3d(clamped.x, clamped.y, clamped.z));
    }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps, boolean teleport) {
        // 直接对齐而不做插值，原因见 tick() 中的说明
        setPos(x, y, z);
        setRot(yaw, pitch);
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** 客户端收到一份姿态快照时调用。偏移量相对于锚点 */
    public void applyPose(double anchorX, double anchorY, double anchorZ,
                          Vector3f[] positions, Quaternionf[] rotations) {
        // 姿态包里的锚点与刚体来自同一个服务端 tick
        // 直接采用它，避免原版量化位移包晚一拍造成实体与模型分离
        setPos(anchorX, anchorY, anchorZ);
        xOld = anchorX;
        yOld = anchorY;
        zOld = anchorZ;

        Vec3[] world = new Vec3[PART_COUNT];
        for (int i = 0; i < PART_COUNT; i++) {
            world[i] = new Vec3(
                    anchorX + positions[i].x,
                    anchorY + positions[i].y,
                    anchorZ + positions[i].z);
        }
        poseBuffer.push(world, rotations);
    }

    public boolean hasPose() {
        return poseBuffer.hasPose();
    }

    /**
     * 解析出本帧要绘制的姿态。在读任何部件之前先调用一次，好让
     * 渲染器和相机取到同一时刻的姿势
     */
    public void samplePose(float partialTick) {
        poseBuffer.sample(partialTick);
    }

    /** 某个部件在世界中的位置，取自采样后的姿态 */
    public Vec3 partWorldPosition(int index) {
        return poseBuffer.position(index);
    }

    /** 某个部件的朝向，取自采样后的姿态 */
    public Quaternionf partRotation(int index, Quaternionf dest) {
        return poseBuffer.rotation(index, dest);
    }

    /** 服务端的刚体变换，用于构建姿态包 */
    public RigidBody body(int index) {
        return skeleton.part(BodyPart.values()[index]);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    static {
        // 如果在开发期间模型和包对不上，就大声报错
        if (PART_COUNT != 6) {
            CorpseLab.LOGGER.warn("Ragdoll part count is {}, expected 6", PART_COUNT);
        }
    }
}
