package com.pyrostella.corpselab.physics;

import org.joml.Vector3d;

/**
 * One sample point of a rigid body resting against one solid world box.
 *
 * <p>The normal is chosen once, when the contact is generated, and held for the whole step:
 * re-picking the least-penetrating axis every substep makes a body sitting on an edge flip
 * between normals and buzz.</p>
 */
public final class Contact {

    /** Blocks per second at which pre-existing overlap is pushed out. */
    private static final double RECOVERY_SPEED = 2.0D;

    /** 冲击速度低于这个值时不反弹，否则躺在地上的尸体会一直颤 */
    private static final double RESTITUTION_THRESHOLD = 2.0D;

    public final RigidBody body;
    private final Vector3d localPoint = new Vector3d();
    private final Aabb box;
    private final Vector3d normal = new Vector3d();

    public double staticFriction = 0.9D;
    public double dynamicFriction = 0.8D;
    /** Flesh does not bounce. Kept configurable for tuning. */
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

    /** Depth of {@code p} inside {@code box} measured along {@code normal}. Negative when outside. */
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

        // Where this material point was at the start of the substep, for static friction.
        this.prevWorldPoint.set(this.localPoint);
        this.body.prevRot.transform(this.prevWorldPoint).add(this.body.prevPos);

        // 一个子步允许推出的总距离是"这点本子步陷进去的深度 + 固定的恢复速度"，
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

        // Cap how much overlap is undone in one substep.
        //
        // Undoing a deep overlap all at once turns into velocity: the position solve
        // derives speed from the distance moved, so ejecting a limb 0.4 blocks within a
        // 3 ms substep hands it 128 blocks/s and fires it across the map. That happens for
        // real whenever a body is driven into terrain or spawned inside a block.
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
     * Static friction: undo the tangential drift of this material point since the substep
     * began, up to the Coulomb limit. This is what stops a settled body sliding downhill.
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

    /** True when this contact is carrying weight from below, rather than a wall or ceiling. */
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

        // Dynamic friction, bounded by the Coulomb cone against this substep's normal impulse.
        //
        // The normal impulse is the velocity we are about to cancel along the normal, not
        // the accumulated position-level lambda: that lambda shrinks as the position solver
        // spreads its budget over several iterations, and tying friction to it let free-mode
        // corpses slide across the floor for tens of blocks.
        Vector3d tangent = new Vector3d(v).fma(-vn, this.normal);
        double speed = tangent.length();
        if (speed > 1.0e-9D) {
            double limit = this.dynamicFriction * Math.abs(vn);
            dv.set(tangent).mul(-Math.min(limit, speed) / speed);
        }

        // Restitution, suppressed for slow impacts or resting bodies jitter forever.
        double bounce = Math.abs(this.normalVelocityBefore) < RESTITUTION_THRESHOLD
                ? 0.0D
                : this.restitution;
        dv.fma(-vn + Math.max(-bounce * this.normalVelocityBefore, 0.0D), this.normal);

        XpbdMath.applyPairCorrection(this.body, this.worldPoint, null, null, dv, 0.0D, h, true);
    }
}
