package com.pyrostella.corpselab.plan;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import com.pyrostella.corpselab.physics.PhysicsWorld;
import com.pyrostella.corpselab.physics.RigidBody;
import com.pyrostella.corpselab.physics.SelfCollision;
import com.pyrostella.corpselab.physics.SphericalJoint;

import net.minecraft.world.phys.Vec3;

/**
 * 用 {@link BodyPlan} 搭出来的一具布娃娃：N 个刚体 + 关节，跑在 XPBD 求解器上。
 *
 * <p>和 Tumble 的 {@code RagdollSkeleton} 的区别：那个写死了 6 个人形部件，
 * 这个的部件数量、尺寸、拓扑全部来自 BodyPlan——所以原版模型和 geo 模型都能喂进来。</p>
 *
 * <p>坐标转换：原版模型空间是 {@code scale(-1,-1,1)} 加 1.501 格抬升，所以
 * 模型点 {@code (x,y,z)} 对应世界偏移 {@code (-x, -y + 1.501, z)}。旋转同样要过这个翻转：
 * 绕模型 X 轴转 θ，在刚体空间里就是绕 X 轴转 -θ。</p>
 */
public final class PlanRagdoll {

    /** 原版 {@code LivingEntityRenderer} 的抬升常量 */
    private static final double LIFT = 1.501D;

    /** 自碰撞球半径相对短半轴的系数：略小于半宽，免得同一条肢体上的球互相顶 */
    private static final double SPHERE_RADIUS = 0.92D;

    /** 自碰撞球沿最长轴的落点最多三个，够挡住四肢插进躯干 */
    private static final int MAX_SPHERES = 3;

    private final BodyPlan plan;
    private final PhysicsWorld world = new PhysicsWorld();
    private final RigidBody[] bodies;
    private final boolean articulated;

    /** 零姿态：每个盒子绕自己的枢轴不转 */
    private static PlanPose.Frame restFrame(BodyPlan.Box box) {
        return new PlanPose.Frame(new Quaterniond(), new Vector3d(box.px(), box.py(), box.pz()));
    }

    /**
     * 不继承姿势的构造器（存档重建、或不需要姿势的场合）。
     */
    public PlanRagdoll(BodyPlan plan, double x, double y, double z, double yaw, Vec3 impulse) {
        this(plan, x, y, z, yaw, impulse, null);
    }

    /**
     * @param plan    骨架
     * @param x       生成点（脚底）
     * @param y       生成点
     * @param z       生成点
     * @param yaw     朝向（弧度）
     * @param impulse 初速度，通常取死亡的动量
     * @param pose    每个盒子的姿势，来自 {@link PlanPose#frames}；null 表示站着不动的零姿态
     */
    public PlanRagdoll(BodyPlan plan, double x, double y, double z, double yaw, Vec3 impulse,
                       @Nullable PlanPose.Frame[] pose) {
        this.plan = plan;
        this.bodies = new RigidBody[plan.boxCount()];
        this.articulated = PlanConfig.mode() == PlanConfig.PlanMode.ARTICULATED;

        Quaterniond orientation = new Quaterniond().rotateY(yaw);
        Vector3d[] centres = new Vector3d[plan.boxCount()];

        for (BodyPlan.Box box : plan.boxes()) {
            PlanPose.Frame frame = pose == null ? restFrame(box) : pose[box.index()];

            // 姿势把盒子绕枢轴转到新位置，盒子仍然以中心为原点做刚体
            Vector3d centre = posedCentre(box, frame);
            centres[box.index()] = centre;

            RigidBody body = new RigidBody(
                    new Vector3d(box.hx(), box.hy(), box.hz()), box.mass());

            // 模型空间 → 世界偏移，再按朝向旋转
            Vector3d offset = toBodyOffset(centre);
            orientation.transform(offset);
            body.pos.set(x + offset.x, y + offset.y, z + offset.z);
            body.rot.set(orientation).mul(flipToBody(frame.rot()));

            body.linearDamping = 0.9998D;
            body.angularDamping = 0.995D;

            this.bodies[box.index()] = body;
            this.world.bodies.add(body);
        }

        for (BodyPlan.Joint joint : plan.joints()) {
            BodyPlan.Box child = plan.boxes().get(joint.child());
            BodyPlan.Box parent = plan.boxes().get(joint.parent());
            PlanPose.Frame childFrame = pose == null ? restFrame(child) : pose[joint.child()];
            PlanPose.Frame parentFrame = pose == null ? restFrame(parent) : pose[joint.parent()];

            // 关节长在子部件的枢轴上（肩、髋、脖子），而不是两个盒子中心的中点：
            // 这样姿势绕关节转，铰接模式的硬投影也不会把姿势抹掉
            Vector3d pivot = childFrame.origin();

            Vector3d anchorChild = localAnchor(child, childFrame, centres[joint.child()], pivot);
            Vector3d anchorParent = localAnchor(parent, parentFrame, centres[joint.parent()], pivot);

            SphericalJoint constraint = new SphericalJoint(
                    this.bodies[joint.child()], anchorChild, new Quaterniond(),
                    this.bodies[joint.parent()], anchorParent, new Quaterniond());
            constraint.limits(75.0D, -45.0D, 45.0D);
            // 铰接模式：子体位置由父体锚点决定，只能绕锚点转
            constraint.rigidAttach = this.articulated;

            this.world.constraints.add(constraint);
        }

        if (PlanConfig.selfCollision()) {
            buildSelfCollision(pose, centres);
        }

        if (impulse != null && impulse.lengthSqr() > 1.0E-6D) {
            for (RigidBody body : this.bodies) {
                body.linVel.set(impulse.x, impulse.y, impulse.z);
            }
        }
    }

    // ---------- 姿势与锚点换算 ----------

    /** 盒子中心在姿势下的模型空间位置：枢轴 + 绕枢轴旋转后的偏移 */
    private static Vector3d posedCentre(BodyPlan.Box box, PlanPose.Frame frame) {
        Vector3d offset = new Vector3d(box.cx(), box.cy(), box.cz())
                .sub(box.px(), box.py(), box.pz());
        frame.rot().transform(offset);
        return new Vector3d(frame.origin()).add(offset);
    }

    /** 模型空间点（或偏移）→ 刚体空间的偏移；Y 翻转，位置还要加抬升 */
    private static Vector3d toBodyOffset(Vector3d model) {
        return new Vector3d(-model.x, -model.y + LIFT, model.z);
    }

    /** 只翻转方向、不加抬升，用于点之间的差 */
    private static Vector3d toBodyDirection(Vector3d model) {
        return new Vector3d(-model.x, -model.y, model.z);
    }

    /** 模型空间的旋转 → 刚体空间的旋转（翻转 X、Y 轴，角度不变） */
    private static Quaterniond flipToBody(Quaterniond model) {
        return new Quaterniond(-model.x, -model.y, model.z, model.w);
    }

    /** 模型空间的一个点，换算成这个盒子刚体的局部坐标 */
    private static Vector3d localAnchor(BodyPlan.Box box, PlanPose.Frame frame,
                                        Vector3d posedCentre, Vector3d modelPoint) {
        Vector3d offset = toBodyDirection(new Vector3d(modelPoint).sub(posedCentre));
        Quaterniond inverse = flipToBody(frame.rot());
        inverse.invert();
        inverse.transform(offset);
        return offset;
    }

    // ---------- 自碰撞 ----------

    /**
     * 给没被关节直接连起来的盒子两两加球-球约束：手臂不能插进躯干，腿不能穿过另一条腿。
     *
     * <p>球沿每个盒子的最长轴摆 1-3 个，半径取两个短半轴里较小的那个再收一点，
     * 所以细长的腿是几颗小球串起来，而不是一个包住整条腿的大球。</p>
     */
    private void buildSelfCollision(@Nullable PlanPose.Frame[] pose, Vector3d[] centres) {
        int count = plan.boxCount();
        if (count < 2) {
            return;
        }

        boolean[][] jointed = new boolean[count][count];
        for (BodyPlan.Joint joint : plan.joints()) {
            jointed[joint.child()][joint.parent()] = true;
            jointed[joint.parent()][joint.child()] = true;
        }

        List<List<Vector3d>> spheres = new ArrayList<>(count);
        double[] radii = new double[count];
        for (BodyPlan.Box box : plan.boxes()) {
            List<Vector3d> list = new ArrayList<>(MAX_SPHERES);
            radii[box.index()] = spheresFor(box, list);
            spheres.add(list);
        }

        // 出生就叠在一起的部件（四足生物的前腿与胸腔、蜷着的尾巴）不参与，
        // 否则一生成就被推开，整具尸体当场炸开
        SelfCollision collision = new SelfCollision();
        // 铰接模式下位置会被 snapChild 钉回父体，每次推力都可能变成拧劲儿，
        // 所以限制单次推开的距离，让重叠在几十个子步里慢慢化开
        if (this.articulated) {
            collision.maxCorrection = 0.01D;
        }

        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                if (jointed[i][j]) {
                    continue;
                }
                if (overlapsAtSpawn(pose, centres[i], centres[j], radii[i], radii[j])) {
                    continue;
                }
                collision.addPair(this.bodies[i], spheres.get(i), radii[i],
                        this.bodies[j], spheres.get(j), radii[j]);
            }
        }

        if (collision.pairCount() > 0) {
            this.world.constraints.add(collision);
        }
    }

    /** 两个盒子在生成时是否已经互相嵌着（用枢轴位置近似算球心距离） */
    private boolean overlapsAtSpawn(@Nullable PlanPose.Frame[] pose, Vector3d centreA, Vector3d centreB,
                                    double radiusA, double radiusB) {
        // 生成时的世界位置已经写进刚体，直接用刚体当前变换算最准
        Vector3d a = toBodyOffset(centreA);
        Vector3d b = toBodyOffset(centreB);
        return a.distance(b) < (radiusA + radiusB) * 1.05D;
    }

    /**
     * 沿最长轴给一个盒子摆球。
     *
     * @return 球的半径
     */
    private static double spheresFor(BodyPlan.Box box, List<Vector3d> out) {
        double hx = box.hx();
        double hy = box.hy();
        double hz = box.hz();

        int axis = 0;
        double longest = hx;
        if (hy > longest) {
            axis = 1;
            longest = hy;
        }
        if (hz > longest) {
            axis = 2;
            longest = hz;
        }

        double shortA = axis == 0 ? hy : hx;
        double shortB = axis == 2 ? hy : hz;
        double radius = Math.max(0.01D, Math.min(shortA, shortB) * SPHERE_RADIUS);

        int count = (int) Math.round(longest / radius);
        count = Math.max(1, Math.min(MAX_SPHERES, count));

        if (count == 1) {
            out.add(new Vector3d());
            return radius;
        }

        double span = Math.max(0.0D, longest - radius);
        for (int i = 0; i < count; i++) {
            double t = -span + 2.0D * span * i / (count - 1);
            Vector3d centre = new Vector3d();
            if (axis == 0) {
                centre.x = t;
            } else if (axis == 1) {
                centre.y = t;
            } else {
                centre.z = t;
            }
            out.add(centre);
        }
        return radius;
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
