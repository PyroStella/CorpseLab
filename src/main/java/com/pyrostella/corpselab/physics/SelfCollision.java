package com.pyrostella.corpselab.physics;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps unconnected parts of one assembly from passing through each other.
 *
 * <p>Each part is approximated by a short chain of spheres strung along its long axis
 * rather than tested as an oriented box. A box-box manifold is far more code and far more
 * ways to jitter, and on a body that is mostly limbs nobody can tell the difference.</p>
 *
 * <p>Parts joined by a joint are <em>not</em> registered: they are supposed to overlap at
 * the shoulder or hip, and pushing them apart would fight the joint.</p>
 */
public final class SelfCollision implements Constraint {

    private record Sphere(RigidBody body, Vector3d centre, double radius) {
    }

    /** Every pair is stored as two matching lists, so the solver is one flat loop. */
    private final List<Sphere> first = new ArrayList<>();
    private final List<Sphere> second = new ArrayList<>();

    private final Vector3d pointA = new Vector3d();
    private final Vector3d pointB = new Vector3d();
    private final Vector3d delta = new Vector3d();

    /** Compliance keeps the push soft, so a settling pile eases apart instead of popping. */
    public double compliance = 0.0002D;

    @Override
    public boolean iterated() {
        return false;
    }

    /** Registers every sphere of one part against every sphere of another. */
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

    /** Deepest current overlap across all registered pairs, in blocks. Zero when clear. */
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
