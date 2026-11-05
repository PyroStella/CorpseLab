package com.pyrostella.corpselab.entity;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.CorpseLabRegistry;
import com.pyrostella.corpselab.config.CorpseLabConfig;
import com.pyrostella.corpselab.net.RagdollPosePacket;
import com.pyrostella.corpselab.net.CorpseLabNetwork;
import com.pyrostella.corpselab.physics.RigidBody;
import com.pyrostella.corpselab.ragdoll.BodyPart;
import com.pyrostella.corpselab.ragdoll.LimbPose;
import com.pyrostella.corpselab.ragdoll.RagdollSkeleton;
import net.minecraft.nbt.CompoundTag;
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

/**
 * 布娃娃本身：普通世界空间里的一个普通 entity，在服务端持有
 * 一套物理装配体，并带着自己的骑乘者一起移动
 *
 * <p>玩家骑乘这个 entity，正是这个决定让其余一切都变得简单。原版
 * 已经会把乘客固定在载具上，已经会拒绝让乘客行走，也已经会把
 * 相机指向乘客所在的位置。子关卡方案所需的那套传送、冻结和
 * 自定义相机工作，在这里一概都用不上
 */
public class RagdollEntity extends Entity {

    public static final int PART_COUNT = BodyPart.values().length;

    private static final EntityDataAccessor<Optional<UUID>> OWNER =
            SynchedEntityData.defineId(RagdollEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    /** 同步到客户端，好让操作提示不再提供一个按了也没反应的键 */
    private static final EntityDataAccessor<Boolean> CAN_GET_UP =
            SynchedEntityData.defineId(RagdollEntity.class, EntityDataSerializers.BOOLEAN);

    /** 仅服务端持有。客户端上为 null，客户端只绘制收到的内容 */
    private RagdollSkeleton skeleton;

    /** 客户端渲染状态：世界空间中的快照，延迟一段时间后回放 */
    private final PoseBuffer poseBuffer = new PoseBuffer(PART_COUNT);

    private int ticksAlive;

    /** 翻滚达到目标速度的快慢。一个 tick 是 1/20 秒，所以这个值已经算很快 */
    private static final double ACCELERATION = 0.25D;

    /** 肢体偏离视线多远仍可被抓住，单位为方块 */
    private static final double GRAB_PICK_RADIUS = 0.6D;

    /** 来自骑乘者的最新翻滚输入，每收到一个包就被替换一次 */
    private float rollStrafe;
    private float rollForward;

    private int impactDamageCooldown;

    /** 身体连续贴靠在某个物体上的 tick 数 */
    private int groundedTicks;

    /** 谁在抓着这具身体、抓的是哪个肢体、从何时开始。仅服务端 */
    private UUID grabberId;
    private int grabbedPart = -1;

    /** 在 mod 主动放开骑乘者期间置位，好让守卫放行这次下车 */
    private boolean releasing;

    public RagdollEntity(EntityType<? extends RagdollEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;

        // 肢体最远会被画到超出这个 entity 自身小包围盒一个方块的位置，所以
        // 如果交给视锥剔除去管，快速移动的身体就会消失一两帧：包围盒
        // 已经移出视野，而肢体明明还清楚地留在屏幕上
        this.noCulling = true;
    }

    /** 在玩家所在的位置生成一具布娃娃，并把玩家安置到它上面 */
    public static RagdollEntity spawnFor(Player player) {
        Level level = player.level();
        RagdollEntity ragdoll = new RagdollEntity(CorpseLabRegistry.RAGDOLL.get(), level);

        Vec3 feet = player.position();
        ragdoll.setPos(feet.x, feet.y, feet.z);
        ragdoll.entityData.set(OWNER, Optional.of(player.getUUID()));

        // 从玩家实际被绘制时的姿态开始，这样正在奔跑的玩家双腿会留在
        // 原来的位置，而不是瞬间跳到中立站姿
        ragdoll.skeleton = new RagdollSkeleton(feet.x, feet.y, feet.z,
                RagdollSkeleton.facingFromYaw(player.yBodyRot),
                CorpseLabConfig.SERVER.partSelfCollision.get(),
                LimbPose.of(player));
        ragdoll.skeleton.setCollider(new LevelCollider(level));
        ragdoll.skeleton.world().maxSpeed = CorpseLabConfig.SERVER.maxLaunchSpeed.get();

        // 把玩家自身的运动带进布娃娃里，这样从边沿跑出去的身体会
        // 继续往前飞，而不是直接往下掉
        Vec3 motion = player.getDeltaMovement().scale(20.0D);
        ragdoll.skeleton.launch(new Vector3d(motion.x, motion.y, motion.z));

        level.addFreshEntity(ragdoll);
        player.startRiding(ragdoll, true);
        return ragdoll;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(OWNER, Optional.empty());
        entityData.define(CAN_GET_UP, false);
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

    public int ticksAlive() {
        return ticksAlive;
    }

    @Override
    public void tick() {
        ticksAlive++;

        if (level().isClientSide) {
            // 这个 entity 只是追踪和剔除用的锚点，每个可见肢体的位置
            // 都来自姿态包里的世界坐标。把上一帧的位置钉住，可以避免它
            // 自己的包围盒滞后，这对剔除有意义，但对肢体画在哪里
            // 已经没有影响了
            xOld = getX();
            yOld = getY();
            zOld = getZ();

            // 在这里而不是在包到达时把最新的姿态提为当前姿态，这样
            // 参与混合的两份姿态永远来自相邻的 tick
            poseBuffer.onClientTick();
            return;
        }

        if (skeleton == null) {
            discard();
            return;
        }

        serverTick();
    }

    /** 活着的布娃娃的行为：一个能挣扎、会受伤、也能起身的骑乘者 */
    protected void serverTick() {
        applyRollInput();
        applyGrab();
        stepPhysics();
        updateGrounded();
        applyImpactDamage();

        if (checkExpiry()) {
            return;
        }

        sendPose();

        if (getFirstPassenger() == null && ticksAlive > 5) {
            discard();
        }
    }

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
        if (skeleton == null || !CorpseLabConfig.SERVER.grabEnabled.get()) {
            return false;
        }

        Vec3 eye = grabber.getEyePosition();
        Vec3 look = grabber.getLookAngle();
        double reach = CorpseLabConfig.SERVER.grabReach.get();

        int best = -1;
        double bestDistance = Double.MAX_VALUE;

        for (BodyPart part : BodyPart.values()) {
            Vector3d pos = skeleton.part(part).pos;
            Vec3 toPart = new Vec3(pos.x, pos.y, pos.z).subtract(eye);

            double along = toPart.dot(look);
            if (along < 0.0D || along > reach) {
                continue;
            }

            // 肢体偏离视线多远，以及它沿视线方向有多近
            double offAxis = toPart.subtract(look.scale(along)).length();
            if (offAxis > GRAB_PICK_RADIUS || along >= bestDistance) {
                continue;
            }
            bestDistance = along;
            best = part.ordinal();
        }

        if (best < 0) {
            return false;
        }

        grabberId = grabber.getUUID();
        grabbedPart = best;
        return true;
    }

    public void releaseGrab() {
        grabberId = null;
        grabbedPart = -1;
    }

    /** 有人正抓着这具身体时为 true */
    public boolean isGrabbed() {
        return grabbedPart >= 0 && grabberId != null;
    }

    public boolean isGrabbedBy(Player player) {
        return grabberId != null && grabberId.equals(player.getUUID());
    }

    /**
     * 把被抓住的肢体拖向抓取者的手
     *
     * <p>这里调的是速度，而不是直接把位置设过去，身体在移动途中
     * 因此仍会与世界碰撞：被拖进墙里的尸体会卡在墙上而不是穿
     * 过去，于是落在手的后面，抓握随即断开
     */
    protected void applyGrab() {
        if (grabbedPart < 0 || grabberId == null) {
            return;
        }

        Player grabber = level().getPlayerByUUID(grabberId);
        if (grabber == null || !grabber.isAlive() || grabber.isSpectator()) {
            releaseGrab();
            return;
        }

        Vec3 hand = grabber.getEyePosition()
                .add(grabber.getLookAngle().scale(CorpseLabConfig.SERVER.grabHoldDistance.get()));

        RigidBody body = skeleton.part(BodyPart.values()[grabbedPart]);
        Vec3 delta = hand.subtract(body.pos.x, body.pos.y, body.pos.z);

        if (delta.length() > CorpseLabConfig.SERVER.grabBreakDistance.get()) {
            releaseGrab();
            return;
        }

        double strength = CorpseLabConfig.SERVER.grabStrength.get();
        body.linVel.set(delta.x * strength, delta.y * strength, delta.z * strength);
    }

    /** 记录骑乘者客户端发来的翻滚输入 */
    public void setRollInput(float strafe, float forward) {
        this.rollStrafe = strafe;
        this.rollForward = forward;
    }

    /**
     * 让倒地的玩家能挣扎
     *
     * <p>输入方向会被转成绕其横向水平轴的旋转，翻滚实际上做的
     * 就是这个；如果换成沿输入方向直接推身体，只会让它在地上
     * 打滑
     */
    private void applyRollInput() {
        if (!CorpseLabConfig.SERVER.rollEnabled.get()) {
            return;
        }
        if (!(getFirstPassenger() instanceof Player rider)) {
            return;
        }

        double strafe = rollStrafe;
        double forward = rollForward;

        // 输入会被消费掉，所以松开按键就能停下翻滚，即使没有包来说这件事
        rollStrafe = 0.0F;
        rollForward = 0.0F;

        if (strafe == 0.0D && forward == 0.0D) {
            return;
        }

        double yaw = Math.toRadians(rider.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);

        // Minecraft 的 yaw 为 0 时朝向 +Z，所以前方是 (-sin, 0, cos)、右方是 (cos, 0, sin)
        Vector3d direction = new Vector3d(
                -sin * forward + cos * strafe,
                0.0D,
                cos * forward + sin * strafe);

        if (direction.lengthSquared() < 1.0e-6D) {
            return;
        }
        direction.normalize();

        // 挣扎不是一个地面能抵抗的推力，所以这里也不按推力来建模
        //
        // 之前两次尝试都失败了，两次都是实测出来的，不是猜的。只加力矩毫无
        // 作用：平躺的躯干绕水平轴一转只是晃一下，因为摩擦和五条连着
        // 的肢体把它固定住了，而且每 tick 旋转都会被重新吸收掉（角速度
        // 维持在 4 rad/s，身体在 4 个 tick 里只移动了 0.0006 个方块）
        // 而直接给速度会从另一个方向败在同一个原因上：接触求解器的
        // 摩擦预算与法向冲量成正比，轻松超过一次步行速度的推力，于是
        // 它在同一个 tick 之内就把注入抵消掉了
        //
        // 玩家拖着自己往前，是在撬地面而不是在地面上滑动，所以
        // 身体直接被平移。这段代码跑在求解之前，于是子步积分器会
        // 把它当成新的静止位置记录下来，而不是当成一次速度
        // 尖峰，同时接触依然能把身体死死挡在墙上
        double step = CorpseLabConfig.SERVER.rollSpeed.get() / 20.0D;
        Vector3d axis = new Vector3d(0.0D, 1.0D, 0.0D).cross(direction);

        for (BodyPart part : BodyPart.values()) {
            RigidBody body = skeleton.part(part);
            body.pos.x += direction.x * step;
            body.pos.z += direction.z * step;
        }

        // 纯视觉效果：让身体翻滚着走，而不是平滑滑行
        skeleton.torso().angVel.fma(CorpseLabConfig.SERVER.rollSpin.get(), axis);
    }

    /** 在骑乘者的身体被狠狠撞到硬物上时对其造成伤害 */
    private void applyImpactDamage() {
        CorpseLabConfig.Server config = CorpseLabConfig.SERVER;
        if (!config.impactDamageEnabled.get()) {
            return;
        }
        if (impactDamageCooldown > 0) {
            impactDamageCooldown--;
            return;
        }
        if (!(getFirstPassenger() instanceof Player rider)) {
            return;
        }

        double threshold = config.impactDamageThreshold.get();
        double impact = skeleton.lastImpact();
        if (impact < threshold) {
            return;
        }

        double damage = Math.min((impact - threshold) * config.impactDamageMultiplier.get(),
                config.impactDamageMax.get());
        if (damage <= 0.0D) {
            return;
        }

        // 用一个原版伤害类型，这样就不需要注册数据驱动的伤害类型
        // 已经在倒地状态再受伤不会开启新的布娃娃，因为身上挂着
        // 载具的玩家永远不满足条件
        rider.hurt(damageSources().flyIntoWall(), (float) damage);
        impactDamageCooldown = config.impactDamageCooldownTicks.get();
    }

    /** @return 若布娃娃在本 tick 结束则返回 true */
    private boolean checkExpiry() {
        // 死掉的骑乘者不能被留在身体上继续骑着
        if (getFirstPassenger() instanceof Player rider && !rider.isAlive()) {
            getUp();
            return true;
        }
        if (ticksAlive >= CorpseLabConfig.SERVER.safetyTimeoutTicks.get()) {
            getUp();
            return true;
        }
        if (CorpseLabConfig.SERVER.expireWhenSlow.get() && canGetUp()
                && skeleton.isSettled(CorpseLabConfig.SERVER.releaseSpeedThreshold.get())) {
            getUp();
            return true;
        }
        return false;
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

    /** 这具布娃娃正主动放开骑乘者时为 true */
    public boolean isReleasing() {
        return releasing;
    }

    /** 结束这具布娃娃，让骑乘者在身体停下的位置重新站起来 */
    public void getUp() {
        // 下车守卫会拦下玩家提前离开，所以 mod 必须自己声明这次
        // 释放，否则它会把自己也拦下
        releasing = true;

        Entity rider = getFirstPassenger();
        if (rider != null) {
            rider.stopRiding();
            // 让玩家站在身体上，而不是陷进身体里
            rider.teleportTo(getX(), getY() + 0.1D, getZ());
            rider.setDeltaMovement(Vec3.ZERO);
        }
        discard();
    }

    /**
     * 统计身体已经倒在地上多久
     *
     * <p>身体一离开地面计数就会归零，所以擦过边沿或者在
     * 途中弹了一下的身体，必须先真正稳定下来，它的玩家
     * 才能起来
     */
    private void updateGrounded() {
        if (skeleton.isGrounded()) {
            groundedTicks++;
        } else {
            groundedTicks = 0;
        }
        entityData.set(CAN_GET_UP, canGetUp());
    }

    public boolean canGetUp() {
        if (ticksAlive < CorpseLabConfig.SERVER.minGetUpTicks.get()) {
            return false;
        }
        if (!CorpseLabConfig.SERVER.requireGroundedToGetUp.get()) {
            return true;
        }
        return groundedTicks >= CorpseLabConfig.SERVER.groundedTicksBeforeGetUp.get();
    }

    /** {@link #canGetUp()} 的客户端可见副本，供操作提示使用 */
    public boolean canGetUpSynced() {
        return entityData.get(CAN_GET_UP);
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
        if (!hasPassenger(passenger)) {
            return;
        }
        // 让骑乘者坐在身体质心的正上方
        //
        // 不能放在它下方：趴着的布娃娃质心离地大约五分之一个方块，向下
        // 偏一点就会把骑乘者塞进地板里，第三人称相机会和地形碰撞
        // 并被拉得很近，最后玩家看到的就只有贴在自己两英寸外
        // 的自己脑袋
        moveFunction.accept(passenger, getX(), getY() + 0.2D, getZ());
    }

    @Override
    public double getPassengersRidingOffset() {
        return 0.0D;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps, boolean teleport) {
        // 直接对齐而不做插值，原因见 tick() 中的说明
        setPos(x, y, z);
        setRot(yaw, pitch);
    }

    @Override
    public boolean isPickable() {
        return false;
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
    protected void readAdditionalSaveData(CompoundTag tag) {
        // 布娃娃是临时实体。存档下来也没有值得恢复的物理状态，而复活
        // 它只会在世界里留下一具没有骑乘者的身体
        discard();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
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
