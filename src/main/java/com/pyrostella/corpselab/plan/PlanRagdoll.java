package com.pyrostella.corpselab.plan;

import java.util.List;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import com.pyrostella.corpselab.physics.PhysicsWorld;
import com.pyrostella.corpselab.physics.RigidBody;
import com.pyrostella.corpselab.physics.SphericalJoint;

import net.minecraft.world.phys.Vec3;

/**
 * 用 {@link BodyPlan} 搭出来的一具布娃娃：N 个刚体 + 关节，跑在 XPBD 求解器上。
 *
 * <p>和 Tumble 的 {@code RagdollSkeleton} 的区别：那个写死了 6 个人形部件，
 * 这个的部件数量、尺寸、拓扑全部来自 BodyPlan——所以原版模型和 geo 模型都能喂进来。</p>
 *
 * <p>坐标转换：原版模型空间是 {@code scale(-1,-1,1)} 加 1.501 格抬升，所以
 * 模型点 {@code (x,y,z)} 对应世界偏移 {@code (-x, -y + 1.501, z)}。</p>
 */
public final class PlanRagdoll {

    /** 原版 {@code LivingEntityRenderer} 的抬升常量 */
    private static final double LIFT = 1.501D;

    private final BodyPlan plan;
    private final PhysicsWorld world = new PhysicsWorld();
    private final RigidBody[] bodies;
    private final boolean articulated;

    /**
     * @param plan    骨架
     * @param x       生成点（脚底）
     * @param y       生成点
     * @param z       生成点
     * @param yaw     朝向（弧度）
     * @param impulse 初速度，通常取死亡的动量
     */
    public PlanRagdoll(BodyPlan plan, double x, double y, double z, double yaw, Vec3 impulse) {
        this.plan = plan;
        this.bodies = new RigidBody[plan.boxCount()];
        this.articulated = PlanConfig.mode() == PlanConfig.PlanMode.ARTICULATED;

        Quaterniond orientation = new Quaterniond().rotateY(yaw);

        for (BodyPlan.Box box : plan.boxes()) {
            RigidBody body = new RigidBody(
                    new Vector3d(box.hx(), box.hy(), box.hz()), box.mass());

            // 模型空间 → 世界偏移，再按朝向旋转
            Vector3d offset = new Vector3d(-box.cx(), -box.cy() + LIFT, box.cz());
            orientation.transform(offset);
            body.pos.set(x + offset.x, y + offset.y, z + offset.z);
            body.rot.set(orientation);

            body.linearDamping = 0.9998D;
            body.angularDamping = 0.995D;

            this.bodies[box.index()] = body;
            this.world.bodies.add(body);
        }

        for (BodyPlan.Joint joint : plan.joints()) {
            BodyPlan.Box child = plan.boxes().get(joint.child());
            BodyPlan.Box parent = plan.boxes().get(joint.parent());

            // 锚点取两个盒子中心的中点，分别转到各自的局部系
            double mx = (child.cx() + parent.cx()) * 0.5D;
            double my = (child.cy() + parent.cy()) * 0.5D;
            double mz = (child.cz() + parent.cz()) * 0.5D;

            Vector3d anchorChild = new Vector3d(mx - child.cx(), my - child.cy(), mz - child.cz());
            Vector3d anchorParent = new Vector3d(mx - parent.cx(), my - parent.cy(), mz - parent.cz());

            SphericalJoint constraint = new SphericalJoint(
                    this.bodies[joint.child()], anchorChild, new Quaterniond(),
                    this.bodies[joint.parent()], anchorParent, new Quaterniond());
            constraint.limits(75.0D, -45.0D, 45.0D);
            // 铰接模式：子体位置由父体锚点决定，只能绕锚点转
            constraint.rigidAttach = this.articulated;

            this.world.constraints.add(constraint);
        }

        if (impulse != null && impulse.lengthSqr() > 1.0E-6D) {
            for (RigidBody body : this.bodies) {
                body.linVel.set(impulse.x, impulse.y, impulse.z);
            }
        }
    }

    public void step(double dt) {
        this.world.step(dt);

        if (this.articulated) {
            // 求解器内部已经把锚点拉在一起了，但接触求解排在约束之后，
            // 落地那一帧仍可能被地面推开一个缝。这里再补一次硬投影，
            // 保证同步出去之前关节一定是闭合的。
            for (var constraint : this.world.constraints) {
                if (constraint instanceof SphericalJoint joint && joint.rigidAttach) {
                    joint.snapChild();
                }
            }
        }
    }

    public void setCollider(com.pyrostella.corpselab.physics.WorldCollider collider) {
        this.world.collider = collider;
    }

    public BodyPlan plan() {
        return this.plan;
    }

    public List<RigidBody> bodies() {
        return List.of(this.bodies);
    }

    public RigidBody body(int index) {
        return this.bodies[index];
    }

    public boolean isSettled() {
        return this.world.maxBodySpeed() < 0.05D && this.world.isGrounded();
    }
}
