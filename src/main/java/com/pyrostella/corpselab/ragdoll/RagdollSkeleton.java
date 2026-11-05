package com.pyrostella.corpselab.ragdoll;

import com.pyrostella.corpselab.physics.PhysicsWorld;
import com.pyrostella.corpselab.physics.RigidBody;
import com.pyrostella.corpselab.physics.SelfCollision;
import com.pyrostella.corpselab.physics.SphericalJoint;
import com.pyrostella.corpselab.physics.WorldCollider;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一具人形布娃娃：六个刚体和五个关节，先按站姿装配再放手
 *
 * <p>求解器对 Minecraft 一无所知。调用方提供 {@link WorldCollider}
 * 再把结果变换读出来。几何来自 {@link HumanoidGeometry}
 * 所以装配结果与将要绘制在其上的模型对齐
 * 而不是与手写的偏移对齐
 */
public final class RagdollSkeleton {

    /** 指向世界 +Y 的骨骼，表示为一个关节坐标系，其局部 X 轴即该骨骼 */
    private static final Quaterniond BONE_UP = new Quaterniond().rotateZ(Math.toRadians(90.0D));
    /** 指向世界 -Y 的骨骼 */
    private static final Quaterniond BONE_DOWN = new Quaterniond().rotateZ(Math.toRadians(-90.0D));

    private final Map<BodyPart, RigidBody> parts = new EnumMap<>(BodyPart.class);
    private final PhysicsWorld world = new PhysicsWorld();

    private SelfCollision selfCollisionConstraint;

    /**
     * 把 Minecraft 实体的 yaw 换算成渲染器期望的身体朝向
     *
     * <p>这半个转向并不是随意的。原版绘制人形时先做 {@code rotateY(180 - yaw)}
     * 再做 {@code scale(-1, -1, 1)}，而本渲染器复用了同样的模型部件
     * 所以身体自身的旋转必须带上同样的半圈
     * 少了它，每具布娃娃出生时都会朝后
     */
    public static double facingFromYaw(double yawDegrees) {
        return Math.toRadians(180.0D - yawDegrees);
    }

    private final LimbPose pose;

    /**
     * @param feetX  人物脚部的世界坐标
     * @param facing 身体朝向，单位为弧度，来自 {@link #facingFromYaw}
     */
    public RagdollSkeleton(double feetX, double feetY, double feetZ, double facing) {
        this(feetX, feetY, feetZ, facing, true, LimbPose.STANDING);
    }

    public RagdollSkeleton(double feetX, double feetY, double feetZ, double facing, boolean selfCollision) {
        this(feetX, feetY, feetZ, facing, selfCollision, LimbPose.STANDING);
    }

    /**
     * @param pose 玩家模型当时所处的姿态，使身体从被绘制的位置开始
     *             而不是跳到中立站姿
     */
    public RagdollSkeleton(double feetX, double feetY, double feetZ, double facing,
                           boolean selfCollision, LimbPose pose) {
        this.pose = pose;
        Quaterniond orientation = new Quaterniond().rotateY(facing);

        for (BodyPart part : BodyPart.values()) {
            RigidBody body = new RigidBody(
                    new Vector3d(part.halfWidth, part.halfHeight, part.halfDepth), part.mass);

            Vector3d offset = HumanoidGeometry.restOffset(part, pose, new Vector3d());
            orientation.transform(offset);
            body.pos.set(feetX + offset.x, feetY + offset.y, feetZ + offset.z);

            // 先身体 yaw，再叠加部件自身的动画旋转
            body.rot.set(orientation).mul(
                    HumanoidGeometry.bodyRotation(part, pose, new Quaterniond()));

            // 线性阻尼保持得很轻：它是空气阻力，而不是刹车。每个子步 0.999 时
            // 被抛出的身体在一秒内就失去三分之一的速度，于是爆炸只会让它
            // 下坠而抛不动人。角阻尼保持得更高，因为不受约束的旋转
            // 才是一堆身体静止后还在发抖的原因
            body.linearDamping = 0.9998D;
            body.angularDamping = 0.995D;

            parts.put(part, body);
            world.bodies.add(body);
        }

        buildJoints();

        if (selfCollision) {
            buildSelfCollision();
        }
    }

    /**
     * 注册每一对没有关节相连的部件。躯干与其余五个部件都相连
     * 因此它不会出现在这里
     */
    private void buildSelfCollision() {
        SelfCollision collision = new SelfCollision();

        BodyPart[] limbs = {BodyPart.HEAD, BodyPart.ARM_LEFT, BodyPart.ARM_RIGHT,
                BodyPart.LEG_LEFT, BodyPart.LEG_RIGHT};

        for (int i = 0; i < limbs.length; i++) {
            for (int j = i + 1; j < limbs.length; j++) {
                BodyPart first = limbs[i];
                BodyPart second = limbs[j];
                collision.addPair(
                        parts.get(first), spheresFor(first), sphereRadius(first),
                        parts.get(second), spheresFor(second), sphereRadius(second));
            }
        }

        // 比默认更硬：推力太软时，四肢会在渐渐安定下来的身体自重下
        // 陷进另一肢，实践中所谓的"四肢互相穿模"
        // 大部分就是这个样子
        collision.compliance = 0.00005D;

        world.constraints.add(collision);
        selfCollisionConstraint = collision;
    }

    /**
     * 没有关节相连的部件之间最深的重叠量，单位为格。自碰撞关闭时
     * 始终为零，因为此时不做任何跟踪
     */
    public double worstSelfOverlap() {
        return selfCollisionConstraint == null ? 0.0D : selfCollisionConstraint.worstOverlap();
    }

    /**
     * 沿部件长轴分布的球心，位于身体空间
     *
     * <p>每个肢体三个，一直排到两端。只在半高处放两个球时，每个肢体
     * 外侧 12% 的区域（手和脚）完全没有碰撞体积
     * 这正是肢体互相穿透肉眼可见的原因：摆动幅度最大、看上去最明显的
     * 部位恰恰是没有参与检测的部位
     */
    private static List<Vector3d> spheresFor(BodyPart part) {
        if (part == BodyPart.HEAD) {
            return List.of(new Vector3d(0.0D, 0.0D, 0.0D));
        }
        double reach = part.halfHeight - sphereRadius(part);
        return List.of(
                new Vector3d(0.0D, reach, 0.0D),
                new Vector3d(0.0D, 0.0D, 0.0D),
                new Vector3d(0.0D, -reach, 0.0D));
    }

    /**
     * 刻意取略小于两腿间隙一半的值
     *
     * <p>原版的腿宽 0.25，但两个枢轴只相距 0.2375
     * 若取满内切半径，模型并排放置的两条肢体
     * 就会长期冲突：求解器每个 tick 都在推，永远解不开
     * 安定后的身体还带着肉眼可见的重叠。改为按间隙定尺寸
     * 是仍能让相邻肢体相互贴靠的最大半径
     */
    private static double sphereRadius(BodyPart part) {
        if (part == BodyPart.HEAD) {
            return 0.22D;
        }
        return 0.115D;
    }

    private void buildJoints() {
        // 每条肢体都挂在模型上真实存在的点：脖子、肩、胯。锚点由该点
        // 在两个部件各自的坐标系中推导而来，所以无论布娃娃以什么
        // 姿态开始都能保持正确
        joinAt(BodyPart.HEAD, BONE_UP, 50.0D, -70.0D, 70.0D);
        joinAt(BodyPart.ARM_RIGHT, BONE_DOWN, 100.0D, -45.0D, 45.0D);
        joinAt(BodyPart.ARM_LEFT, BONE_DOWN, 100.0D, -45.0D, 45.0D);
        joinAt(BodyPart.LEG_RIGHT, BONE_DOWN, 70.0D, -25.0D, 25.0D);
        joinAt(BodyPart.LEG_LEFT, BONE_DOWN, 70.0D, -25.0D, 25.0D);
    }

    private void joinAt(BodyPart limb, Quaterniond boneFrame,
                        double swingDegrees, double minTwistDegrees, double maxTwistDegrees) {
        Vector3d attachment = HumanoidGeometry.attachment(limb);

        join(parts.get(BodyPart.TORSO),
                HumanoidGeometry.anchorIn(BodyPart.TORSO, attachment, pose, new Vector3d()), boneFrame,
                parts.get(limb),
                HumanoidGeometry.anchorIn(limb, attachment, pose, new Vector3d()), boneFrame,
                swingDegrees, minTwistDegrees, maxTwistDegrees);
    }

    private void join(RigidBody a, Vector3d anchorA, Quaterniond frameA,
                      RigidBody b, Vector3d anchorB, Quaterniond frameB,
                      double swingDegrees, double minTwistDegrees, double maxTwistDegrees) {
        SphericalJoint joint = new SphericalJoint(a, anchorA, frameA, b, anchorB, frameB);
        joint.limits(swingDegrees, minTwistDegrees, maxTwistDegrees);
        world.constraints.add(joint);
    }

    public PhysicsWorld world() {
        return world;
    }

    public RigidBody part(BodyPart part) {
        return parts.get(part);
    }

    public RigidBody torso() {
        return parts.get(BodyPart.TORSO);
    }

    public void setCollider(WorldCollider collider) {
        world.collider = collider;
    }

    private double lastImpact;

    /** 把布娃娃推进一个 Minecraft tick */
    public void tick() {
        double before = world.maxBodySpeed();
        world.step(1.0D / 20.0D);
        // 本 tick 中世界夺走了多少速度。重着地或撞墙
        // 在这里表现为大幅下降，而滑行到停止则不会
        lastImpact = Math.max(0.0D, before - world.maxBodySpeed());
    }

    /** 上一个 tick 因碰撞损失的速度，单位为格每秒 */
    public double lastImpact() {
        return lastImpact;
    }

    /**
     * 对每个部件施加抛射冲量，让整个身体被抛出
     * 而不是被推在某一条肢体上撕散
     */
    public void launch(Vector3d velocity) {
        for (RigidBody body : parts.values()) {
            body.linVel.add(velocity);
        }
    }

    /** 任一部件贴靠表面时为真 */
    public boolean isGrounded() {
        return world.isGrounded();
    }

    /** 每个部件的速度都降到每秒 {@code threshold} 格以下时为真 */
    public boolean isSettled(double threshold) {
        return world.maxBodySpeed() < threshold;
    }

    /** 质心，玩家实体应当保持在这个位置 */
    public Vector3d centreOfMass(Vector3d dest) {
        dest.set(0.0D);
        double total = 0.0D;
        for (Map.Entry<BodyPart, RigidBody> entry : parts.entrySet()) {
            double mass = entry.getKey().mass;
            dest.fma(mass, entry.getValue().pos);
            total += mass;
        }
        return dest.div(total);
    }
}
