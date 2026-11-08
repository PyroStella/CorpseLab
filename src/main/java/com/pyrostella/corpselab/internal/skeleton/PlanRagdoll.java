package com.pyrostella.corpselab.internal.skeleton;

import com.pyrostella.corpselab.api.model.BodyPlan;
import com.pyrostella.corpselab.internal.config.PlanConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import com.pyrostella.corpselab.physics.constraint.Constraint;
import com.pyrostella.corpselab.physics.PhysicsWorld;
import com.pyrostella.corpselab.physics.RigidBody;
import com.pyrostella.corpselab.physics.collision.SelfCollision;
import com.pyrostella.corpselab.physics.constraint.SphericalJoint;
import com.pyrostella.corpselab.physics.collision.WorldCollider;
import com.pyrostella.corpselab.internal.skeleton.RagdollSkeleton;

import net.minecraft.world.phys.Vec3;

/**
 * 用 {@link BodyPlan} 搭出来的一具布娃娃：N 个刚体（+ 可选关节），跑在 XPBD 求解器上
 *
 * <p>和 Tumble 的 {@code RagdollSkeleton} 的区别：那个写死了 6 个人形部件，这个的部件数量
 * 尺寸、拓扑全部来自 BodyPlan——所以原版模型和 geo 模型都能喂进来。</p>
 *
 * <p>坐标转换：原版模型空间是 {@code scale(-1,-1,1)} 加 1.501 格抬升，所以模型点
 * {@code (x,y,z)} 对应世界偏移 {@code (-x, -y + 1.501, z)}。旋转同样要过这个翻转。</p>
 */
public final class PlanRagdoll {

    /** 原版 {@code LivingEntityRenderer} 的抬升常量 */
    private static final double LIFT = 1.501D;

    /** 自碰撞球沿最长轴最多摆几个，够挡住四肢插进躯干 */
    private static final int MAX_SPHERES = 3;

    /** 两个盒子中心近到这个程度（枢轴重合，比如帽子和脑袋）就算不出骨头方向，退回继承父盒子 */
    private static final double BONE_DEGENERATE = 1.0e-4D;

    /** 兜底判据：球心距离小于半径和的这个比例，就当成天生叠在一起，不再指望推开它们 */
    private static final double DEEP_OVERLAP = 0.5D;

    private static final Vector3d X_AXIS = new Vector3d(1.0D, 0.0D, 0.0D);

    private final BodyPlan plan;
    private final PhysicsWorld world = new PhysicsWorld();
    private final RigidBody[] bodies;
    private final List<RigidBody> orderedBodies;
    private final boolean articulated;

    /**
     * 不继承姿势的构造器（存档重建、或不需要姿势的场合）
     */
    public PlanRagdoll(BodyPlan plan, double x, double y, double z, double yaw, Vec3 impulse) {
        this(plan, x, y, z, yaw, impulse, null);
    }

    /**
     * @param plan    骨架
     * @param x       生成点（脚底）
     * @param y       生成点
     * @param z       生成点
     * @param yaw     实体朝向（弧度）；内部会补上原版模型那半圈，见 {@link RagdollSkeleton#facingFromYaw}
     * @param impulse 初速度，通常取死亡的动量
     * @param pose    每个盒子的姿势，来自 {@link PlanPose#frames}；null 表示站着不动的零姿态
     */
    public PlanRagdoll(BodyPlan plan, double x, double y, double z, double yaw, Vec3 impulse,
                       @Nullable PlanPose.Frame[] pose) {
        this(plan,x,y,z,yaw,impulse,pose,PlanConfig.mode() == PlanConfig.PlanMode.ARTICULATED);
    }

    public PlanRagdoll(BodyPlan plan,double x,double y,double z,double yaw,Vec3 impulse,
                       @Nullable PlanPose.Frame[] pose,boolean articulated) {
        this.plan = plan;
        this.bodies = new RigidBody[plan.boxCount()];
        this.articulated = articulated;
        this.world.iterations = PlanConfig.solverIterations();

        // 原版把模型画成 rotateY(180 - yaw) 再 scale(-1,-1,1)。少了前半圈的话，尸体在 yaw=0
        // 时是背对着的，yaw 与朝向的关系也变成镜像：生物往右转头，尸体往左转
        Quaterniond orientation = new Quaterniond()
                .rotateY(RagdollSkeleton.facingFromYaw(Math.toDegrees(yaw)));
        Vector3d[] centres = new Vector3d[plan.boxCount()];

        for (BodyPlan.Box box : plan.boxes()) {
            PlanPose.Frame frame = pose == null ? restFrame(box) : pose[box.index()];

            // 姿势把盒子绕枢轴转到新位置，盒子本身仍然以中心为原点做刚体
            Vector3d centre = posedCentre(box, frame);
            centres[box.index()] = centre;

            RigidBody body = new RigidBody(new Vector3d(box.hx(), box.hy(), box.hz()), box.mass());

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

        this.orderedBodies = List.of(this.bodies);

        if (this.articulated) {
            buildJoints(pose, centres, orientation);
        }

        // 自碰撞两种模式都建：无关节时它是唯一把散开的部件约束成一堆的东西
        // 有骨架时它挡住手臂插进胸腔、腿穿过另一条腿
        if (PlanConfig.selfCollision()) {
            buildSelfCollision();
        }

        if (impulse != null) {
            for (RigidBody body : this.bodies) {
                body.linVel.set(impulse.x, impulse.y, impulse.z);
            }
        }
    }

    /** 零姿态：盒子绕自己的枢轴不转 */
    private static PlanPose.Frame restFrame(BodyPlan.Box box) {
        return new PlanPose.Frame(new Quaterniond(), new Vector3d(box.px(), box.py(), box.pz()));
    }

    // ---------- 关节 ----------

    /**
     * 按骨架的关节表建软关节：位置靠弹性拉回，被拽开太远就断开
     *
     * <p>FREE 模式根本不调用这个方法：那个模式下一个关节都不建。</p>
     */
    private void buildJoints(@Nullable PlanPose.Frame[] pose, Vector3d[] centres, Quaterniond orientation) {
        int count = this.plan.boxCount();
        int[] parentOf = new int[count];
        Arrays.fill(parentOf, -1);
        for (BodyPlan.Joint joint : this.plan.joints()) {
            parentOf[joint.child()] = joint.parent();
        }

        Vector3d[] boneDirs = new Vector3d[count];
        boolean[] visiting = new boolean[count];

        for (BodyPlan.Joint joint : this.plan.joints()) {
            BodyPlan.Box child = this.plan.boxes().get(joint.child());
            BodyPlan.Box parent = this.plan.boxes().get(joint.parent());
            PlanPose.Frame childFrame = pose == null ? restFrame(child) : pose[joint.child()];
            PlanPose.Frame parentFrame = pose == null ? restFrame(parent) : pose[joint.parent()];

            // 关节长在子部件的枢轴上
            Vector3d pivot = childFrame.origin();
            Vector3d anchorChild = localAnchor(childFrame, centres[joint.child()], pivot);
            Vector3d anchorParent = localAnchor(parentFrame, centres[joint.parent()], pivot);

            Vector3d bone = boneDirection(joint.child(), parentOf, centres, boneDirs, visiting);
            Quaterniond boneFrame = new Quaterniond()
                    .rotationTo(X_AXIS, orientation.transform(toBodyDirection(bone)));
            Quaterniond frameChild = new Quaterniond(this.bodies[joint.child()].rot).invert().mul(boneFrame);
            Quaterniond frameParent = new Quaterniond(this.bodies[joint.parent()].rot).invert().mul(boneFrame);

            SphericalJoint constraint = new SphericalJoint(
                    this.bodies[joint.child()], anchorChild, frameChild,
                    this.bodies[joint.parent()], anchorParent, frameParent);
            constraint.limits(75.0D, -45.0D, 45.0D);
            // 软关节：允许被拉开一点点，拉过头就断
            // 参数按原版大小的部件校准；大型几何不能因自重不同而把同样的关节拉断
            double loadScale = Math.max(1.0D, (child.mass() + parent.mass()) / 0.5D);
            constraint.compliance = PlanConfig.jointCompliance() / loadScale;
            constraint.breakDistance = PlanConfig.jointBreakDistance();

            this.world.constraints.add(constraint);
        }
    }

    /**
     * 子盒子相对父盒子的骨轴方向（模型空间单位向量）
     *
     * <p>取两个盒子中心之差：肢体从躯干中心朝外长，这个方向就是骨头指的方向。帽子和脑袋
     * 这类中心几乎重合的部件算不出方向，就继承父盒子的方向；根盒子退回模型空间的 +Y。</p>
     */
    private static Vector3d boneDirection(int box, int[] parentOf, Vector3d[] centres,
                                          Vector3d[] memo, boolean[] visiting) {
        Vector3d cached = memo[box];
        if (cached != null) {
            return cached;
        }
        if (visiting[box]) {
            // 骨架成环：给一个安全的默认值，防止无限递归
            return new Vector3d(0.0D, 1.0D, 0.0D);
        }
        visiting[box] = true;

        int parent = parentOf[box];
        Vector3d dir;
        if (parent < 0) {
            dir = new Vector3d(0.0D, 1.0D, 0.0D);
        } else {
            Vector3d raw = new Vector3d(centres[box]).sub(centres[parent]);
            dir = raw.lengthSquared() > BONE_DEGENERATE * BONE_DEGENERATE
                    ? raw.normalize()
                    : new Vector3d(boneDirection(parent, parentOf, centres, memo, visiting));
        }

        visiting[box] = false;
        memo[box] = dir;
        return dir;
    }

    // ---------- 姿势与锚点换算 ----------

    /** 盒子中心在姿势下的模型空间位置：枢轴 + 绕枢轴旋转后的偏移 */
    private static Vector3d posedCentre(BodyPlan.Box box, PlanPose.Frame frame) {
        Vector3d offset = new Vector3d(box.cx(), box.cy(), box.cz())
                .sub(box.px(), box.py(), box.pz());
        frame.rot().transform(offset);
        return new Vector3d(frame.origin()).add(offset);
    }

    /** 模型空间点 → 刚体空间的偏移；Y 翻转，位置还要加抬升 */
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
    private static Vector3d localAnchor(PlanPose.Frame frame, Vector3d posedCentre, Vector3d modelPoint) {
        Vector3d offset = toBodyDirection(new Vector3d(modelPoint).sub(posedCentre));
        Quaterniond inverse = flipToBody(frame.rot());
        inverse.invert();
        inverse.transform(offset);
        return offset;
    }

    // ---------- 自碰撞 ----------

    /**
     * 给没被关节直接连起来的盒子两两加球-球约束：手臂不能插进躯干，腿不能穿过另一条腿
     *
     * <p>球沿每个盒子的最长轴摆 1-3 个，半径取两个短半轴里较小的那个，所以细长的腿是几颗
     * 小球串起来，而不是一个包住整条腿的大球。</p>
     */
    private void buildSelfCollision() {
        int count = this.plan.boxCount();
        if (count < 2) {
            return;
        }

        // 有骨架时直接相连的父子部件贴在一起是正常的，别让它们互相排斥
        // 无骨架时它们只是两块独立的肉，照样要挡
        boolean[][] jointed = new boolean[count][count];
        if (this.articulated) {
            for (BodyPlan.Joint joint : this.plan.joints()) {
                jointed[joint.child()][joint.parent()] = true;
                jointed[joint.parent()][joint.child()] = true;
            }
        }

        List<List<Vector3d>> spheres = new ArrayList<>(count);
        double[] radii = new double[count];
        for (BodyPlan.Box box : this.plan.boxes()) {
            List<Vector3d> list = new ArrayList<>(MAX_SPHERES);
            radii[box.index()] = spheresFor(box, list);
            spheres.add(list);
        }

        SelfCollision collision = new SelfCollision();
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                if (jointed[i][j]) {
                    continue;
                }
                // 出生就深深叠在一起的部件（四足生物的前腿与胸腔、鸡的翅膀）不参与
                // 否则一生成就被推开，整具尸体当场炸开。只略微交叠的（两条腿）照样参与
                if (nestedAtSpawn(i, j, spheres.get(i), radii[i], spheres.get(j), radii[j])) {
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

    /**
     * 这两件在生成姿势下是不是<b>天生</b>嵌在一起（本来就这么建模的）
     *
     * <p>判据是"一方的中心落在另一方的盒子里"：鸡的翅膀整个埋在身体里、四足生物的前腿
     * 长在胸腔里，都是这种。它们本来就不该被推开，否则一生成整具尸体就炸开。</p>
     *
     * <p>不能用"中心距离 < 半径和"来判：人形两条腿的中心只差 0.2375，两球半径和 0.25
     * 那样腿这一对会从出生就被当成"已经叠着"整对丢掉——腿之间从此再没有任何排斥力
     * 可以一路交叉着贴到一起。</p>
     */
    private boolean nestedAtSpawn(int first, int second, List<Vector3d> firstSpheres, double firstRadius,
                                  List<Vector3d> secondSpheres, double secondRadius) {
        // 铰接模型允许本来就交叠的装饰、翅膜和相邻骨段，不能强行把它们顶开
        if (this.articulated && boxesOverlap(this.bodies[first], this.bodies[second])) return true;
        if (containsCentre(this.bodies[first], this.bodies[second].pos)
                || containsCentre(this.bodies[second], this.bodies[first].pos)) {
            return true;
        }

        // 保留球形代理的深度判据，轻微接触的两条腿仍需要互相碰撞
        double limit = (firstRadius + secondRadius) * DEEP_OVERLAP;
        double limitSq = limit * limit;
        Vector3d a = new Vector3d();
        Vector3d b = new Vector3d();
        for (Vector3d one : firstSpheres) {
            this.bodies[first].toWorld(one, a);
            for (Vector3d two : secondSpheres) {
                this.bodies[second].toWorld(two, b);
                if (a.distanceSquared(b) < limitSq) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 分离轴检测，不把有向盒只在角落交叠的情况漏成自碰撞 */
    private static boolean boxesOverlap(RigidBody a, RigidBody b) {
        Vector3d[] aa = axes(a);
        Vector3d[] bb = axes(b);
        Vector3d delta = new Vector3d(b.pos).sub(a.pos);
        for (Vector3d axis : aa) if (separated(axis, delta, aa, a.halfExtents, bb, b.halfExtents)) return false;
        for (Vector3d axis : bb) if (separated(axis, delta, aa, a.halfExtents, bb, b.halfExtents)) return false;
        for (Vector3d one : aa) for (Vector3d two : bb) {
            Vector3d axis = new Vector3d(one).cross(two);
            if (axis.lengthSquared() > 1e-12 && separated(axis, delta, aa, a.halfExtents, bb, b.halfExtents)) return false;
        }
        return true;
    }

    private static Vector3d[] axes(RigidBody body) {
        return new Vector3d[]{body.rot.transform(new Vector3d(1, 0, 0)),
                body.rot.transform(new Vector3d(0, 1, 0)), body.rot.transform(new Vector3d(0, 0, 1))};
    }

    private static boolean separated(Vector3d axis, Vector3d delta, Vector3d[] aa, Vector3d ah,
                                     Vector3d[] bb, Vector3d bh) {
        double radius = Math.abs(axis.dot(aa[0]))*ah.x + Math.abs(axis.dot(aa[1]))*ah.y
                + Math.abs(axis.dot(aa[2]))*ah.z + Math.abs(axis.dot(bb[0]))*bh.x
                + Math.abs(axis.dot(bb[1]))*bh.y + Math.abs(axis.dot(bb[2]))*bh.z;
                // 仅轻微相交的相邻肢体仍保留碰撞，不把正常两腿之间的约束剔除
        double tolerance = 0.2D * Math.min(Math.min(ah.x, Math.min(ah.y, ah.z)),
                Math.min(bh.x, Math.min(bh.y, bh.z)));
        return Math.abs(axis.dot(delta)) >= radius - tolerance * axis.length();
    }
    /** 世界坐标点是否落在刚体盒内（按刚体自己的朝向判） */
    private static boolean containsCentre(RigidBody box, Vector3d point) {
        Vector3d local = new Vector3d(point).sub(box.pos);
        new Quaterniond(box.rot).invert().transform(local);
        return Math.abs(local.x) <= box.halfExtents.x
                && Math.abs(local.y) <= box.halfExtents.y
                && Math.abs(local.z) <= box.halfExtents.z;
    }

    /**
     * 沿最长轴给一个盒子摆球，球半径取横截面的短半轴，让球正好填满盒子宽度
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

        // 两个短半轴（顺序无关紧要，下面只取较小者）
        double shortA;
        double shortB;
        if (axis == 0) {
            shortA = hy;
            shortB = hz;
        } else if (axis == 1) {
            shortA = hx;
            shortB = hz;
        } else {
            shortA = hx;
            shortB = hy;
        }
        double radius = Math.max(0.01D, Math.min(shortA, shortB));

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

    /**
     * 调试视图用：这个盒子在自碰撞里实际用的球（半径 + 刚体局部坐标里的球心偏移）
     *
     * <p>和求解器读的是同一份数据，所以画出来的就是真正在解的东西。</p>
     */
    public static CollisionSpheres collisionSpheres(BodyPlan.Box box) {
        List<Vector3d> offsets = new ArrayList<>(MAX_SPHERES);
        double radius = spheresFor(box, offsets);
        List<Vec3> points = new ArrayList<>(offsets.size());
        for (Vector3d offset : offsets) {
            points.add(new Vec3(offset.x, offset.y, offset.z));
        }
        return new CollisionSpheres(radius, List.copyOf(points));
    }

    /**
     * @param radius  球半径
     * @param offsets 球心相对盒子中心的偏移，刚体局部坐标
     */
    public record CollisionSpheres(double radius, List<Vec3> offsets) {
    }

    // ---------- 步进与查询 ----------

    public void step(double dt) {
        this.world.step(dt);

        // 断裂判定按 tick 走，不放子步里，理由见 SphericalJoint#updateBreak
        for (Constraint constraint : this.world.constraints) {
            if (constraint instanceof SphericalJoint joint) {
                joint.updateBreak();
            }
        }
    }

    public void setCollider(WorldCollider collider) {
        this.world.collider = collider;
    }

    public BodyPlan plan() {
        return this.plan;
    }

    public List<RigidBody> bodies() {
        return this.orderedBodies;
    }

    public RigidBody body(int index) {
        return this.bodies[index];
    }

    /** 按 BodyPlan 里的质量计算整具尸体的世界坐标质心 */
    public Vector3d centreOfMass(Vector3d dest) {
        dest.set(0.0D);
        double totalMass = 0.0D;
        for (BodyPlan.Box box : this.plan.boxes()) {
            double mass = box.mass();
            dest.fma(mass, this.bodies[box.index()].pos);
            totalMass += mass;
        }
        if (totalMass > 0.0D) {
            return dest.div(totalMass);
        }

        // 损坏或第三方生成的计划可能把所有质量写成零
        // 此时退回几何中心，至少不要把锚点送到世界原点
        for (RigidBody body : this.bodies) {
            dest.add(body.pos);
        }
        return this.bodies.length == 0 ? dest : dest.div(this.bodies.length);
    }

    /** 平移整套刚体，让质心落到指定的世界坐标 */
    public void moveCentreTo(double x, double y, double z) {
        Vector3d offset = centreOfMass(new Vector3d()).negate().add(x, y, z);
        for (RigidBody body : this.bodies) {
            body.pos.add(offset);
        }
    }

    /** 所有还连着的关节里，锚点被拉开最多的那一个的距离 */
    public double maxAnchorGap() {
        double worst = 0.0D;
        for (Constraint constraint : this.world.constraints) {
            if (constraint instanceof SphericalJoint joint && !joint.isBroken()) {
                worst = Math.max(worst, joint.anchorGap());
            }
        }
        return worst;
    }

    /** 已经断开的关节数量。调试渲染和测试用 */
    public int brokenJointCount() {
        int broken = 0;
        for (Constraint constraint : this.world.constraints) {
            if (constraint instanceof SphericalJoint joint && joint.isBroken()) {
                broken++;
            }
        }
        return broken;
    }

    /** 按实际仍有效的物理关节判断连通性，FREE 模式仅包含基准块 */
    public boolean[] connectedTo(int base) {
        int[] components = new int[this.bodies.length];
        var indices = new java.util.IdentityHashMap<RigidBody,Integer>();
        for (int i = 0; i < components.length; i++) { components[i] = i; indices.put(this.bodies[i],i); }
        for (Constraint constraint : this.world.constraints) {
            if (constraint instanceof SphericalJoint joint && !joint.isBroken()) {
                int a = component(components,indices.get(joint.bodyA));
                int b = component(components,indices.get(joint.bodyB));
                components[a] = b;
            }
        }
        boolean[] connected = new boolean[components.length];
        int root = component(components,base);
        for (int i = 0; i < connected.length; i++) connected[i] = component(components,i) == root;
        return connected;
    }

    private static int component(int[] components,int index) {
        while (components[index] != index) { components[index] = components[components[index]]; index = components[index]; }
        return index;
    }
    /** 保存实际姿态和关节断裂，避免重载后把断肢当作仍连接的肢体 */
    public net.minecraft.nbt.CompoundTag saveState() {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putBoolean("Articulated",this.articulated);
        var parts = new net.minecraft.nbt.ListTag();
        for (RigidBody body : this.bodies) {
            var part = new net.minecraft.nbt.CompoundTag();
            part.putDouble("X",body.pos.x); part.putDouble("Y",body.pos.y); part.putDouble("Z",body.pos.z);
            part.putDouble("QX",body.rot.x); part.putDouble("QY",body.rot.y); part.putDouble("QZ",body.rot.z); part.putDouble("QW",body.rot.w);
            part.putDouble("VX",body.linVel.x); part.putDouble("VY",body.linVel.y); part.putDouble("VZ",body.linVel.z);
            part.putDouble("WX",body.angVel.x); part.putDouble("WY",body.angVel.y); part.putDouble("WZ",body.angVel.z);
            parts.add(part);
        }
        tag.put("Parts",parts);
        var broken = new net.minecraft.nbt.ListTag();
        for (Constraint constraint : this.world.constraints) if (constraint instanceof SphericalJoint joint)
            broken.add(net.minecraft.nbt.ByteTag.valueOf(joint.isBroken()));
        tag.put("Broken",broken); return tag;
    }

    public void loadState(net.minecraft.nbt.CompoundTag tag) {
        var parts = tag.getList("Parts",10);
        if (parts.size() != this.bodies.length) throw new IllegalArgumentException("刚体存档数量不符");
        for (int i = 0; i < parts.size(); i++) {
            var part = parts.getCompound(i); var body = this.bodies[i];
            body.pos.set(part.getDouble("X"),part.getDouble("Y"),part.getDouble("Z"));
            body.rot.set(part.getDouble("QX"),part.getDouble("QY"),part.getDouble("QZ"),part.getDouble("QW"));
            double rotationLength = body.rot.lengthSquared();
            if (!Double.isFinite(rotationLength) || rotationLength <= 0) {
                throw new IllegalArgumentException("刚体存档旋转无效");
            }
            body.rot.normalize();
            body.linVel.set(part.getDouble("VX"),part.getDouble("VY"),part.getDouble("VZ"));
            body.angVel.set(part.getDouble("WX"),part.getDouble("WY"),part.getDouble("WZ"));
            if (!body.pos.isFinite() || !body.linVel.isFinite() || !body.angVel.isFinite()) {
                throw new IllegalArgumentException("刚体存档位置或速度无效");
            }
        }
        var broken = tag.getList("Broken",1); int index = 0;
        for (Constraint constraint : this.world.constraints) if (constraint instanceof SphericalJoint joint) {
            joint.restoreBroken(index < broken.size() && ((net.minecraft.nbt.ByteTag)broken.get(index)).getAsByte() != 0); index++;
        }
    }

    public boolean isSettled() {
        return this.world.maxBodySpeed() < 0.05D && this.world.isGrounded();
    }
}
