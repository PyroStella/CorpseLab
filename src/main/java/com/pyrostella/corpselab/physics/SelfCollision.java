package com.pyrostella.corpselab.physics;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 防止同一装配体里未相连的部件互相穿透
 *
 * <p>每个部件不是按有向盒来检测，而是用一串沿长轴排开的球近似
 * ——盒对盒的接触流形代码量大得多，能抖动的来源也多得多，而在一个
 * 主要由四肢组成的身体上，没人看得出差别</p>
 *
 * <p>由关节连接的部件<em>不会</em>登记，因为它们在肩或髋处本来就
 * 该重叠，硬把彼此推开只会和关节对着干</p>
 */
public final class SelfCollision implements Constraint {

    private record Sphere(RigidBody body, Vector3d centre, double radius) {
    }

    /** 每个球对拆成两个对齐的列表存放，这样求解器就是一层平铺的循环 */
    private final List<Sphere> first = new ArrayList<>();
    private final List<Sphere> second = new ArrayList<>();

    private final Vector3d pointA = new Vector3d();
    private final Vector3d pointB = new Vector3d();
    private final Vector3d delta = new Vector3d();

    /** 柔度让推力保持柔软，这样正在堆叠的物体会缓缓分开而不是弹开 */
    public double compliance = 0.0002D;

    @Override
    public boolean iterated() {
        return false;
    }

    /** 把两个部件之间的球两两配对登记 */
    public void addPair(RigidBody bodyA, List<Vector3d> centresA, double radiusA,
                        RigidBody bodyB, List<Vector3d> centresB, double radiusB) {
        for (Vector3d one : centresA) {
            for (Vector3d two : centresB) {
                this.first.add(new Sphere(bodyA, new Vector3d(one), radiusA));
                this.second.add(new Sphere(bodyB, new Vector3d(two), radiusB));
            }
        }
    }

    public int pairCount() {
        return this.first.size();
    }

    /** 所有已登记配对里当前最深的重叠量，单位为格；没有重叠时为 0 */
    public double worstOverlap() {
        double worst = 0.0D;
        for (int i = 0; i < this.first.size(); i++) {
            Sphere a = this.first.get(i);
            Sphere b = this.second.get(i);
            a.body().toWorld(a.centre(), this.pointA);
            b.body().toWorld(b.centre(), this.pointB);
            worst = Math.max(worst, (a.radius() + b.radius()) - this.pointA.distance(this.pointB));
        }
        return worst;
    }

    @Override
    public void solvePosition(double h) {
        for (int i = 0; i < this.first.size(); i++) {
            Sphere a = this.first.get(i);
            Sphere b = this.second.get(i);

            a.body().toWorld(a.centre(), this.pointA);
            b.body().toWorld(b.centre(), this.pointB);

            this.delta.set(this.pointB).sub(this.pointA);
            double distance = this.delta.length();
            double minimum = a.radius() + b.radius();
            if (distance >= minimum || distance < 1.0e-9D) {
                continue;
            }

            // 把两个球沿连线推回到相切的位置，各自的反作用由质量与转动惯量分摊
            this.delta.div(distance).mul(-(minimum - distance));
            XpbdMath.applyPairCorrection(a.body(), this.pointA, b.body(), this.pointB,
                    this.delta, this.compliance, h, false);
        }
    }
}
