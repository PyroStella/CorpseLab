package com.pyrostella.corpselab.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 带摆动锥和扭转范围的球窝关节
 *
 * <p>每个刚体各带一个关节坐标系，其局部 X 轴是骨骼方向，局部
 * Y 轴是扭转参考。摆动是两根骨骼轴之间的夹角，扭转则是绕这两根
 * 轴的旋转</p>
 *
 * <p>两个锚点用软连接（{@link #compliance}）拉在一起，所以关节可以被自重和冲击拉开一点
 * 被拉开的距离连续超过 {@link #breakDistance} 若干个 tick 就永久断开，那截肢体真的掉下来。</p>
 */
public final class SphericalJoint implements Constraint {

    private static final Vector3d BONE_AXIS = new Vector3d(1.0D, 0.0D, 0.0D);
    private static final Vector3d TWIST_REF = new Vector3d(0.0D, 1.0D, 0.0D);

    /** 锚点连续这么多个 tick 拉不回来才算断，见 {@link #updateBreak()} */
    private static final int BREAK_TICKS = 3;

    public final RigidBody bodyA;
    public final RigidBody bodyB;

    private final Vector3d localAnchorA = new Vector3d();
    private final Quaterniond localFrameA = new Quaterniond();
    private final Vector3d localAnchorB = new Vector3d();
    private final Quaterniond localFrameB = new Quaterniond();

    /** 摆动锥的半角，单位为弧度 */
    public double swingLimit = Math.toRadians(60.0D);
    public double minTwist = Math.toRadians(-30.0D);
    public double maxTwist = Math.toRadians(30.0D);

    /** 连接的逆刚度；0 表示刚性关节 */
    public double compliance = 0.0D;

    /** 锚点被拉开多少格就彻底断开；0 = 永不断开 */
    public double breakDistance = 0.0D;

    private boolean broken;
    private int breakTicks;

    private final Vector3d worldAnchorA = new Vector3d();
    private final Vector3d worldAnchorB = new Vector3d();
    private final Quaterniond worldFrameA = new Quaterniond();
    private final Quaterniond worldFrameB = new Quaterniond();

    public SphericalJoint(RigidBody bodyA, Vector3d localAnchorA, Quaterniond localFrameA,
                          RigidBody bodyB, Vector3d localAnchorB, Quaterniond localFrameB) {
        this.bodyA = bodyA;
        this.bodyB = bodyB;
        this.localAnchorA.set(localAnchorA);
        this.localFrameA.set(localFrameA);
        this.localAnchorB.set(localAnchorB);
        this.localFrameB.set(localFrameB);
    }

    public SphericalJoint limits(double swingDegrees, double minTwistDegrees, double maxTwistDegrees) {
        this.swingLimit = Math.toRadians(swingDegrees);
        this.minTwist = Math.toRadians(minTwistDegrees);
        this.maxTwist = Math.toRadians(maxTwistDegrees);
        return this;
    }

    /** 两个锚点现在的世界距离：刚性关节为 0，软关节就是被拉开的量 */
    public double anchorGap() {
        refreshFrames();
        return this.worldAnchorA.distance(this.worldAnchorB);
    }

    /** 仅用于恢复已保存的断裂状态 */
    public void restoreBroken(boolean broken) { this.broken = broken; this.breakTicks = 0; }

    public boolean isBroken() {
        return this.broken;
    }

    /**
     * 每 tick 判一次是否该断
     *
     * <p>必须按 tick 判，不能放在 {@link #solvePosition} 里：限位是绕各自质心做的纯角度
     * 修正，一发力就会把锚点甩开几厘米，同一子步后面的 {@code solveAttachment} 再把它拉回来
     * 那点瞬时距离只是限位在正常工作，按子步判会把脑袋这种力臂最长的关节判成断裂。</p>
     */
    public void updateBreak() {
        if (this.broken || this.breakDistance <= 0.0D) {
            return;
        }
        if (anchorGap() > this.breakDistance) {
            if (++this.breakTicks >= BREAK_TICKS) {
                this.broken = true;
            }
        } else {
            this.breakTicks = 0;
        }
    }

    @Override
    public void solvePosition(double h) {
        if (this.broken) {
            return;
        }

        refreshFrames();
        solveSwing(h);
        solveTwist(h);

        // 限位会让各刚体绕自身质心旋转，把两个锚点拉开，所以
        // 连接必须在限位之后的坐标系下求解，否则满载的限位
        // 会在关节处留下一道肉眼可见的缝隙
        refreshFrames();
        solveAttachment(h);
    }

    private void refreshFrames() {
        this.bodyA.toWorld(this.localAnchorA, this.worldAnchorA);
        this.bodyB.toWorld(this.localAnchorB, this.worldAnchorB);
        this.worldFrameA.set(this.bodyA.rot).mul(this.localFrameA);
        this.worldFrameB.set(this.bodyB.rot).mul(this.localFrameB);
    }

    private void solveAttachment(double h) {
        Vector3d corr = new Vector3d(this.worldAnchorB).sub(this.worldAnchorA);
        XpbdMath.applyPairCorrection(this.bodyA, this.worldAnchorA, this.bodyB, this.worldAnchorB,
                corr, this.compliance, h, false);
    }

    private void solveSwing(double h) {
        Vector3d axisA = this.worldFrameA.transform(new Vector3d(BONE_AXIS));
        Vector3d axisB = this.worldFrameB.transform(new Vector3d(BONE_AXIS));

        Vector3d axis = new Vector3d(axisA).cross(axisB);
        double length = axis.length();
        if (length < 1.0e-9D) {
            // 两根骨骼共线：没有摆动需要修正，也没有有效的旋转轴
            return;
        }
        axis.div(length);

        XpbdMath.limitAngle(this.bodyA, this.bodyB, axis, axisA, axisB,
                -this.swingLimit, this.swingLimit, this.compliance, h, Math.PI);
    }

    private void solveTwist(double h) {
        Vector3d axisA = this.worldFrameA.transform(new Vector3d(BONE_AXIS));
        Vector3d axisB = this.worldFrameB.transform(new Vector3d(BONE_AXIS));

        Vector3d axis = new Vector3d(axisA).add(axisB);
        double length = axis.length();
        if (length < 1.0e-9D) {
            return;
        }
        axis.div(length);

        // 把两个扭转参考都投影到垂直于公共轴的平面上
        Vector3d refA = this.worldFrameA.transform(new Vector3d(TWIST_REF));
        refA.fma(-refA.dot(axis), axis);
        Vector3d refB = this.worldFrameB.transform(new Vector3d(TWIST_REF));
        refB.fma(-refB.dot(axis), axis);

        if (refA.lengthSquared() < 1.0e-12D || refB.lengthSquared() < 1.0e-12D) {
            return;
        }
        refA.normalize();
        refB.normalize();

        XpbdMath.limitAngle(this.bodyA, this.bodyB, axis, refA, refB,
                this.minTwist, this.maxTwist, this.compliance, h, Math.PI);
    }
}
