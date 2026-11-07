package com.pyrostella.corpselab.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 盒状刚体，用子步化 XPBD 积分
 *
 * <p>单位沿用 Minecraft 的：位置用格，速度用格每秒
 *
 * <p>刻意不依赖任何 Minecraft 代码，这样求解器可以脱离游戏做无头测试
 */
public final class RigidBody {

    /** 单个子步内超过这么多弧度的修正会被截断，以保证求解器稳定 */
    private static final double MAX_ROTATION_PER_CORRECTION = 0.5D;

    public final Vector3d pos = new Vector3d();
    public final Quaterniond rot = new Quaterniond();

    public final Vector3d linVel = new Vector3d();
    public final Vector3d angVel = new Vector3d();

    /** 当前子步开始时的状态，用于在位置求解之后反推速度 */
    public final Vector3d prevPos = new Vector3d();
    public final Quaterniond prevRot = new Quaterniond();

    /** 盒的半边长，单位为格 */
    public final Vector3d halfExtents = new Vector3d();

    public double invMass;
    /** 对角转动惯量张量的逆，在刚体坐标系下 */
    public final Vector3d invInertia = new Vector3d();

    /** 每个子步乘到速度上；1.0 表示没有阻尼 */
    public double linearDamping = 1.0D;
    public double angularDamping = 1.0D;

    /** 环境参数默认为陆地值，仅浸水部件调整，不改变干燥尸体的物理效果 */
    public double gravityScale = 1.0D;
    public double frictionScale = 1.0D;
    public double supportDamping = 0.85D;
    public boolean onGround;

    private final Vector3d scratch = new Vector3d();

    public RigidBody(Vector3d halfExtents, double mass) {
        this.halfExtents.set(halfExtents);
        setMass(mass);
    }

    /** 设置质量，并按当前半边长推导盒的转动惯量张量 */
    public void setMass(double mass) {
        if (mass <= 0.0D) {
            invMass = 0.0D;
            invInertia.set(0.0D);
            return;
        }
        invMass = 1.0D / mass;

        double w = halfExtents.x * 2.0D;
        double h = halfExtents.y * 2.0D;
        double d = halfExtents.z * 2.0D;

        double ix = mass * (h * h + d * d) / 12.0D;
        double iy = mass * (w * w + d * d) / 12.0D;
        double iz = mass * (w * w + h * h) / 12.0D;

        invInertia.set(1.0D / ix, 1.0D / iy, 1.0D / iz);
    }

    public boolean isStatic() {
        return invMass == 0.0D;
    }

    /** 把位置和姿态推进一个子步，子步长为 {@code h} 秒 */
    void integrate(double h, Vector3d gravity) {
        if (isStatic()) {
            prevPos.set(pos);
            prevRot.set(rot);
            return;
        }

        prevPos.set(pos);
        prevRot.set(rot);

        linVel.fma(h * this.gravityScale, gravity);
        linVel.mul(linearDamping);
        pos.fma(h, linVel);

        angVel.mul(angularDamping);
        applyAngularVelocity(h);
    }

    /** 积分姿态：q += (h/2) * omega_quat * q，然后重新归一化 */
    private void applyAngularVelocity(double h) {
        double wx = angVel.x;
        double wy = angVel.y;
        double wz = angVel.z;

        double qx = rot.x;
        double qy = rot.y;
        double qz = rot.z;
        double qw = rot.w;

        // (wx, wy, wz, 0) * q
        double dx = wx * qw + wy * qz - wz * qy;
        double dy = -wx * qz + wy * qw + wz * qx;
        double dz = wx * qy - wy * qx + wz * qw;
        double dw = -(wx * qx + wy * qy + wz * qz);

        double s = h * 0.5D;
        rot.set(qx + s * dx, qy + s * dy, qz + s * dz, qw + s * dw);
        rot.normalize();
    }

    /** 由约束求解造成的位置变化反推速度 */
    void deriveVelocity(double h) {
        if (isStatic()) {
            return;
        }

        double inv = 1.0D / h;
        linVel.set(pos).sub(prevPos).mul(inv);

        // dq = rot * prevRot^-1，即本子步施加的世界坐标系旋转
        double px = -prevRot.x;
        double py = -prevRot.y;
        double pz = -prevRot.z;
        double pw = prevRot.w;

        double dx = rot.w * px + rot.x * pw + rot.y * pz - rot.z * py;
        double dy = rot.w * py - rot.x * pz + rot.y * pw + rot.z * px;
        double dz = rot.w * pz + rot.x * py - rot.y * px + rot.z * pw;
        double dw = rot.w * pw - rot.x * px - rot.y * py - rot.z * pz;

        double scale = 2.0D * inv;
        angVel.set(dx * scale, dy * scale, dz * scale);
        if (dw < 0.0D) {
            angVel.negate();
        }
    }

    /**
     * 沿 {@code normal} 方向的广义逆质量
     *
     * @param worldPoint 作用点的世界坐标；纯角度约束时传 null
     */
    public double inverseMass(Vector3d normal, Vector3d worldPoint) {
        if (worldPoint == null) {
            scratch.set(normal);
        } else {
            scratch.set(worldPoint).sub(pos).cross(normal);
        }

        rot.transformInverse(scratch);

        double w = scratch.x * scratch.x * invInertia.x
                + scratch.y * scratch.y * invInertia.y
                + scratch.z * scratch.z * invInertia.z;

        return worldPoint == null ? w : w + invMass;
    }

    /**
     * 施加一个位置（或速度层面）的修正
     *
     * @param worldPoint 作用点的世界坐标；纯角度修正时传 null
     */
    public void applyCorrection(Vector3d corr, Vector3d worldPoint, boolean velocityLevel) {
        if (isStatic()) {
            return;
        }

        Vector3d torqueAxis = new Vector3d();
        if (worldPoint == null) {
            torqueAxis.set(corr);
        } else {
            if (velocityLevel) {
                linVel.fma(invMass, corr);
            } else {
                pos.fma(invMass, corr);
            }
            torqueAxis.set(worldPoint).sub(pos).cross(corr);
        }

        // 把力矩轴经逆转动惯量张量变换过去，变换在刚体坐标系下进行
        rot.transformInverse(torqueAxis);
        torqueAxis.mul(invInertia);
        rot.transform(torqueAxis);

        if (velocityLevel) {
            angVel.add(torqueAxis);
        } else {
            applyRotationCorrection(torqueAxis);
        }
    }

    private void applyRotationCorrection(Vector3d axis) {
        double scale = 1.0D;
        double phi = axis.length();
        if (phi * scale > MAX_ROTATION_PER_CORRECTION) {
            scale = MAX_ROTATION_PER_CORRECTION / phi;
        }

        double ax = axis.x * scale;
        double ay = axis.y * scale;
        double az = axis.z * scale;

        double qx = rot.x;
        double qy = rot.y;
        double qz = rot.z;
        double qw = rot.w;

        double dx = ax * qw + ay * qz - az * qy;
        double dy = -ax * qz + ay * qw + az * qx;
        double dz = ax * qy - ay * qx + az * qw;
        double dw = -(ax * qx + ay * qy + az * qz);

        rot.set(qx + 0.5D * dx, qy + 0.5D * dy, qz + 0.5D * dz, qw + 0.5D * dw);
        rot.normalize();
    }

    /** 把刚体坐标系下的点变换到世界坐标系 */
    public Vector3d toWorld(Vector3d local, Vector3d dest) {
        return rot.transform(dest.set(local)).add(pos);
    }

    /** 把刚体坐标系下的方向旋转到世界坐标系 */
    public Vector3d dirToWorld(Vector3d local, Vector3d dest) {
        return rot.transform(dest.set(local));
    }

    /** 本刚体上世界坐标点 {@code worldPoint} 处的速度 */
    public Vector3d pointVelocity(Vector3d worldPoint, Vector3d dest) {
        dest.set(worldPoint).sub(pos).cross(angVel).negate().add(linVel);
        return dest;
    }
}
