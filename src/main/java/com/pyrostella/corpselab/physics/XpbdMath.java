package com.pyrostella.corpselab.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 公用的 XPBD 基础运算
 *
 * <p>实现 Müller、Macklin、Chentanez、Jeschke 和 Kim 在 "Detailed
 * Rigid Body Simulation with Extended Position Based Dynamics"（2020）
 * 中给出的子步化刚体公式。下面两个例程都出自那篇论文，只是改用 Java 表达
 */
public final class XpbdMath {

    private XpbdMath() {
    }

    /**
     * 在两个刚体之间消解 {@code corr}，按广义逆质量
     * 分摊修正量
     *
     * <p>任一刚体都可以为 null（视为不可移动）。世界坐标点传 null
     * 表示这是一次纯角度修正
     *
     * @param compliance 逆刚度；0 表示完全刚性
     * @return 实际施加的拉格朗日乘子，调用方把它当作冲量大小
     */
    public static double applyPairCorrection(RigidBody a, Vector3d pointA,
                                             RigidBody b, Vector3d pointB,
                                             Vector3d corr, double compliance, double h,
                                             boolean velocityLevel) {
        double c = corr.length();
        if (c < 1.0e-12D) {
            return 0.0D;
        }

        Vector3d normal = new Vector3d(corr).div(c);

        double wa = a == null ? 0.0D : a.inverseMass(normal, pointA);
        double wb = b == null ? 0.0D : b.inverseMass(normal, pointB);
        double w = wa + wb;
        if (w < 1.0e-12D) {
            return 0.0D;
        }

        double lambda = c / (w + compliance / (h * h));
        normal.mul(lambda);

        if (a != null) {
            a.applyCorrection(normal, pointA, velocityLevel);
        }
        if (b != null) {
            b.applyCorrection(normal.negate(), pointB, velocityLevel);
        }
        return lambda;
    }

    /**
     * 把 {@code a} 绕轴 {@code n} 转到 {@code b} 的有符号夹角限制在
     * [{@code minAngle}, {@code maxAngle}] 弧度之内。所有关节限位背后的
     * 原语都是它；摆动锥和扭转范围各自只是一次调用
     *
     * @return 限位被突破并施加了修正时为 true
     */
    public static boolean limitAngle(RigidBody body0, RigidBody body1,
                                     Vector3d n, Vector3d a, Vector3d b,
                                     double minAngle, double maxAngle,
                                     double compliance, double h, double maxCorrection) {
        Vector3d c = new Vector3d(a).cross(b);

        double phi = Math.asin(clamp(c.dot(n), -1.0D, 1.0D));
        if (a.dot(b) < 0.0D) {
            phi = Math.PI - phi;
        }
        if (phi > Math.PI) {
            phi -= 2.0D * Math.PI;
        }
        if (phi < -Math.PI) {
            phi += 2.0D * Math.PI;
        }

        if (phi >= minAngle && phi <= maxAngle) {
            return false;
        }

        phi = clamp(phi, minAngle, maxAngle);

        Quaterniond q = new Quaterniond().fromAxisAngleRad(n.x, n.y, n.z, phi);
        Vector3d omega = q.transform(new Vector3d(a)).cross(b);

        double len = omega.length();
        if (len > maxCorrection) {
            omega.mul(maxCorrection / len);
        }

        applyPairCorrection(body0, null, body1, null, omega, compliance, h, false);
        return true;
    }

    public static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
