package com.pyrostella.corpselab.physics;

import org.joml.Vector3d;

/**
 * 刚体抵靠某个世界实体方块的单个采样点
 *
 * <p>法线在接触生成时选定一次，之后在整个步长内保持不变
 * 的原因很简单：每个子步都重新挑穿透最浅的轴，会让停在棱上的
 * 刚体在几个法线之间来回翻转并抖动</p>
 */
public final class Contact {

    /** 已存在的重叠被推出的速度，单位为格每秒 */
    private static final double RECOVERY_SPEED = 2.0D;

    /** 冲击速度低于这个值时不反弹，否则躺在地上的尸体会一直颤 */
    private static final double RESTITUTION_THRESHOLD = 2.0D;

    public final RigidBody body;
    private final Vector3d localPoint = new Vector3d();
    private final Aabb box;
    private final Vector3d normal = new Vector3d();

    public double staticFriction = 0.9D;
    public double dynamicFriction = 0.8D;
    /** 血肉不会反弹，保留可配置是为了调参 */
    public double restitution = 0.0D;

    private final Vector3d worldPoint = new Vector3d();
    private final Vector3d velocity = new Vector3d();
    private final Vector3d prevWorldPoint = new Vector3d();
    private final Vector3d scratch = new Vector3d();

    private double normalVelocityBefore;
    private boolean active;

    /** 本子步还剩多少距离可以把点推出表面；多遍迭代共享，见 {@link #beginSubstep} */
    private double budget;

    Contact(RigidBody body, Vector3d localPoint, Aabb box, Vector3d normal) {
        this.body = body;
        this.localPoint.set(localPoint);
        this.box = box;
        this.normal.set(normal);
    }

    /** 点 {@code p} 沿 {@code normal} 方向深入 {@code box} 的深度。点在盒外时为负 */
    private static double depthAlong(Aabb box, Vector3d p, Vector3d n) {
        if (p.x < box.minX() || p.x > box.maxX()
                || p.y < box.minY() || p.y > box.maxY()
                || p.z < box.minZ() || p.z > box.maxZ()) {
            return -1.0D;
        }
        if (n.x > 0.5D) {
            return box.maxX() - p.x;
        }
        if (n.x < -0.5D) {
            return p.x - box.minX();
        }
        if (n.y > 0.5D) {
            return box.maxY() - p.y;
        }
        if (n.y < -0.5D) {
            return p.y - box.minY();
        }
        if (n.z > 0.5D) {
            return box.maxZ() - p.z;
        }
        return p.z - box.minZ();
    }

    void beginSubstep(double h, int passes) {
        this.active = false;

        this.body.toWorld(this.localPoint, this.worldPoint);
        this.normalVelocityBefore = this.body.pointVelocity(this.worldPoint, this.scratch).dot(this.normal);

        // 该物质点在子步开始时的位置，供静摩擦使用
        this.prevWorldPoint.set(this.localPoint);
        this.body.prevRot.transform(this.prevWorldPoint).add(this.body.prevPos);

        // 一个子步允许推出的总距离是"这点本子步陷进去的深度 + 固定的恢复速度"
        // 分摊到每一遍迭代上：冲击不会被每遍重复化解成过量弹射，后面几遍又还有余额
        // 把被关节拽回去的点重新推出来
        int slices = Math.max(1, passes);
        this.budget = (Math.max(0.0D, -this.normalVelocityBefore) * h + RECOVERY_SPEED * h) / slices;
    }

    void solvePosition(double h) {
        this.body.toWorld(this.localPoint, this.worldPoint);
        double depth = depthAlong(this.box, this.worldPoint, this.normal);
        if (depth <= 0.0D) {
            return;
        }
        this.active = true;

        // 限制单个子步内补回多少重叠
        //
        // 一次性补回很深的穿透会转化成速度，因为位置求解从移动距离
        // 反推速度：在 3 ms 的子步里把一条肢体弹出 0.4 格，等于给它
        // 128 格每秒的速度，会把它甩到地图另一头。刚体被顶进地形、或者
        // 生成在方块内部时，这种情况是真实会发生的
        double corrected = Math.min(depth, this.budget);
        if (corrected <= 0.0D) {
            return;
        }
        this.budget -= corrected;

        Vector3d push = new Vector3d(this.normal).mul(corrected);
        XpbdMath.applyPairCorrection(this.body, this.worldPoint, null, null,
                push, 0.0D, h, false);

        applyStaticFriction(h, corrected);
    }

    /**
     * 静摩擦：撤销这个物质点自子步开始以来的切向漂移，上限是
     * 库仑极限，也正是它让停稳的刚体不再往坡下溜
     */
    private void applyStaticFriction(double h, double corrected) {
        this.body.toWorld(this.localPoint, this.worldPoint);
        Vector3d slide = new Vector3d(this.worldPoint).sub(this.prevWorldPoint);
        slide.fma(-slide.dot(this.normal), this.normal);

        double length = slide.length();
        if (length < 1.0e-9D) {
            return;
        }
        double limit = this.staticFriction * corrected;
        if (length > limit) {
            slide.mul(limit / length);
        }
        XpbdMath.applyPairCorrection(this.body, this.worldPoint, null, null,
                slide.negate(), 0.0D, h, false);
    }

    /** 该接触点是从下方承重、而不是墙面或天花板时为 true */
    boolean isSupporting() {
        return this.active && this.normal.y > 0.5D;
    }

    void solveVelocity(double h) {
        if (!this.active) {
            return;
        }

        this.body.toWorld(this.localPoint, this.worldPoint);
        Vector3d v = this.body.pointVelocity(this.worldPoint, this.velocity);
        double vn = v.dot(this.normal);

        Vector3d dv = new Vector3d();

        // 动摩擦，按库仑锥用本子步的法向冲量限制
        //
        // 这里的法向冲量是即将沿法向抵消掉的那部分速度，而不是
        // 位置层面累积的 lambda：那个 lambda 会随着位置求解把预算
        // 摊到多遍迭代上而缩小，摩擦一旦与它挂钩，自由模式的尸体
        // 就会在地面上滑出几十格
        Vector3d tangent = new Vector3d(v).fma(-vn, this.normal);
        double speed = tangent.length();
        if (speed > 1.0e-9D) {
            double limit = this.dynamicFriction * Math.abs(vn);
            dv.set(tangent).mul(-Math.min(limit, speed) / speed);
        }

        // 恢复系数，慢速冲击时抑制掉，否则停住的刚体会一直抖
        double bounce = Math.abs(this.normalVelocityBefore) < RESTITUTION_THRESHOLD
                ? 0.0D
                : this.restitution;
        dv.fma(-vn + Math.max(-bounce * this.normalVelocityBefore, 0.0D), this.normal);

        XpbdMath.applyPairCorrection(this.body, this.worldPoint, null, null, dv, 0.0D, h, true);
    }
}
