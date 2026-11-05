package com.pyrostella.corpselab.physics;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * The substepped XPBD solver.
 *
 * <p>Contacts are generated once per step against a small margin, then re-evaluated each
 * substep. That is the standard trade: broadphase is the expensive part, and the margin
 * covers the distance a body can move within one step.</p>
 */
public final class PhysicsWorld {

    /** Minecraft's gravity, in blocks per second squared (0.08 blocks/tick² at 20 tps). */
    public static final double MC_GRAVITY = -32.0D;

    /** Contacts are kept alive while a sample point is within this distance of a solid. */
    private static final double CONTACT_MARGIN = 0.04D;

    /** 支撑中的刚体每 tick 保留的速度比例，见 {@link #step} 末尾 */
    private static final double REST_DAMPING = 0.85D;

    /** Hard cap on speed, so nothing can outrun the contact margin within one step. */
    public double maxSpeed = 128.0D;

    public final List<RigidBody> bodies = new ArrayList<>();
    public final List<Constraint> constraints = new ArrayList<>();

    private final List<Contact> contacts = new ArrayList<>();
    private final List<Aabb> solidScratch = new ArrayList<>();

    public final Vector3d gravity = new Vector3d(0.0D, MC_GRAVITY, 0.0D);
    public WorldCollider collider = WorldCollider.EMPTY;

    /**
     * More substeps buy stability far more cheaply than more solver iterations do - that is
     * the central result of the small-substep XPBD paper.
     */
    public int substeps = 16;

    /**
     * 每个子步把位置约束投影几遍。
     *
     * <p>只投一遍时，互相矛盾的硬约束（髋部锚点必须重合 vs 脚不能进地面）只能满足后者，
     * 前者留下残余位移，再被 {@link RigidBody#deriveVelocity} 折算成速度：姿态永远在抖、
     * 永远不静止。多投几遍，它们才会协商出一个折中。</p>
     */
    public int iterations = 1;

    private boolean grounded;

    public void step(double dt) {
        clampSpeeds();
        generateContacts(dt);

        double h = dt / this.substeps;
        for (int s = 0; s < this.substeps; s++) {
            for (RigidBody body : this.bodies) {
                body.integrate(h, this.gravity);
            }
            for (Contact contact : this.contacts) {
                contact.beginSubstep(h, this.iterations);
            }

            for (int iteration = 0; iteration < this.iterations; iteration++) {
                for (Constraint constraint : this.constraints) {
                    // 排斥类约束只在第一遍里投一次，见 Constraint#iterated
                    if (iteration > 0 && !constraint.iterated()) {
                        continue;
                    }
                    constraint.solvePosition(h);
                }
                for (Contact contact : this.contacts) {
                    contact.solvePosition(h);
                }
            }

            for (RigidBody body : this.bodies) {
                body.deriveVelocity(h);
            }
            for (Contact contact : this.contacts) {
                contact.solveVelocity(h);
            }
        }

        settle();
    }

    /**
     * 一 tick 结束时收尾：记录是否有东西踩在地上，并把支撑中的刚体速度压一点。
     *
     * <p>静摩擦在位置层面被多遍迭代摊薄之后，落地尸体仍会以几厘米每秒的速度慢慢滑走，
     * 肉眼非常明显，所以要在这里再收一次。</p>
     */
    private void settle() {
        this.grounded = false;
        for (Contact contact : this.contacts) {
            if (!contact.isSupporting()) {
                continue;
            }
            this.grounded = true;
            contact.body.linVel.mul(REST_DAMPING);
            contact.body.angVel.mul(REST_DAMPING);
        }
    }

    /**
     * Whether anything in the assembly is resting on a surface, as of the last step.
     *
     * <p>Measured from the contacts themselves rather than from height or speed, so a body
     * wedged against a wall or falling past a ledge is not mistaken for one that landed.</p>
     */
    public boolean isGrounded() {
        return this.grounded;
    }

    /** Highest linear speed in the assembly, used to decide when a ragdoll has settled. */
    public double maxBodySpeed() {
        double max = 0.0D;
        for (RigidBody body : this.bodies) {
            max = Math.max(max, body.linVel.length());
        }
        return max;
    }

    private void clampSpeeds() {
        for (RigidBody body : this.bodies) {
            double speed = body.linVel.length();
            if (speed > this.maxSpeed) {
                body.linVel.mul(this.maxSpeed / speed);
            }
        }
    }

    /**
     * Builds the step's contact set.
     *
     * <p>Contacts are <em>speculative</em>: one is created for every sample point close
     * enough to reach a solid within this step, including points still in mid-air. It stays
     * inert until the per-substep depth test says the point is actually inside.</p>
     *
     * <p>That is what stops a body launched at 128 blocks/s - 6.4 blocks in a single tick -
     * from passing straight through a one-block floor: the reach is the body's own travel
     * distance for the step, so the faster it moves, the further ahead it looks.</p>
     */
    private void generateContacts(double dt) {
        this.contacts.clear();

        Vector3d local = new Vector3d();
        Vector3d world = new Vector3d();
        Vector3d normal = new Vector3d();

        for (RigidBody body : this.bodies) {
            if (body.isStatic()) {
                continue;
            }

            double reach = CONTACT_MARGIN + body.linVel.length() * dt;

            this.solidScratch.clear();
            this.collider.collectSolids(sweptBounds(body, dt).inflate(CONTACT_MARGIN), this.solidScratch);
            if (this.solidScratch.isEmpty()) {
                continue;
            }

            for (int i = 0; i < SAMPLE_COUNT; i++) {
                samplePoint(body, i, local);
                body.toWorld(local, world);

                for (Aabb box : this.solidScratch) {
                    if (distanceTo(box, world) > reach) {
                        continue;
                    }
                    pickNormal(box, world, normal, this.solidScratch);
                    this.contacts.add(new Contact(body, local, box, normal));
                }
            }
        }
    }

    /** Distance from a point to a box; zero when the point is inside. */
    private static double distanceTo(Aabb box, Vector3d p) {
        double dx = Math.max(Math.max(box.minX() - p.x, 0.0D), p.x - box.maxX());
        double dy = Math.max(Math.max(box.minY() - p.y, 0.0D), p.y - box.maxY());
        double dz = Math.max(Math.max(box.minZ() - p.z, 0.0D), p.z - box.maxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static final Vector3d[] FACES = {
            new Vector3d(-1.0D, 0.0D, 0.0D), new Vector3d(1.0D, 0.0D, 0.0D),
            new Vector3d(0.0D, -1.0D, 0.0D), new Vector3d(0.0D, 1.0D, 0.0D),
            new Vector3d(0.0D, 0.0D, -1.0D), new Vector3d(0.0D, 0.0D, 1.0D),
    };

    /**
     * Chooses the escape direction, once per contact, and then holds it for the whole step.
     *
     * <p>Outside the box the normal is the dominant axis of separation - for a body falling
     * towards a floor that is the top face, the one it will land on. Inside, it is the
     * shallowest face that is actually exposed.</p>
     */
    private static void pickNormal(Aabb box, Vector3d p, Vector3d out, List<Aabb> neighbours) {
        double dx = p.x - XpbdMath.clamp(p.x, box.minX(), box.maxX());
        double dy = p.y - XpbdMath.clamp(p.y, box.minY(), box.maxY());
        double dz = p.z - XpbdMath.clamp(p.z, box.minZ(), box.maxZ());

        if (dx != 0.0D || dy != 0.0D || dz != 0.0D) {
            double ax = Math.abs(dx);
            double ay = Math.abs(dy);
            double az = Math.abs(dz);
            if (ax >= ay && ax >= az) {
                out.set(Math.signum(dx), 0.0D, 0.0D);
            } else if (ay >= az) {
                out.set(0.0D, Math.signum(dy), 0.0D);
            } else {
                out.set(0.0D, 0.0D, Math.signum(dz));
            }
            return;
        }

        // Inside the box: escape through the shallowest face that is actually exposed.
        //
        // Skipping buried faces is essential in a voxel world. A point resting a hair below
        // a floor surface sits far closer to the block's vertical side face than to its top,
        // so the naive least-penetration answer is sideways - and two adjacent floor blocks
        // then shove the body in opposite directions, which reads in game as a settled
        // ragdoll skating slowly across the ground forever.
        double bestDepth = Double.MAX_VALUE;
        out.set(0.0D, 1.0D, 0.0D);

        for (Vector3d face : FACES) {
            double depth = faceDepth(box, p, face);
            if (depth >= bestDepth || isFaceBuried(box, face, neighbours)) {
                continue;
            }
            bestDepth = depth;
            out.set(face);
        }
    }

    private static double faceDepth(Aabb box, Vector3d p, Vector3d face) {
        if (face.x < 0.0D) {
            return p.x - box.minX();
        }
        if (face.x > 0.0D) {
            return box.maxX() - p.x;
        }
        if (face.y < 0.0D) {
            return p.y - box.minY();
        }
        if (face.y > 0.0D) {
            return box.maxY() - p.y;
        }
        if (face.z < 0.0D) {
            return p.z - box.minZ();
        }
        return box.maxZ() - p.z;
    }

    /** True when another solid abuts this face, making it interior geometry. */
    private static boolean isFaceBuried(Aabb box, Vector3d face, List<Aabb> neighbours) {
        double probeX = (box.minX() + box.maxX()) * 0.5D + face.x * ((box.maxX() - box.minX()) * 0.5D + 0.1D);
        double probeY = (box.minY() + box.maxY()) * 0.5D + face.y * ((box.maxY() - box.minY()) * 0.5D + 0.1D);
        double probeZ = (box.minZ() + box.maxZ()) * 0.5D + face.z * ((box.maxZ() - box.minZ()) * 0.5D + 0.1D);

        for (Aabb other : neighbours) {
            if (other != box && other.contains(probeX, probeY, probeZ)) {
                return true;
            }
        }
        return false;
    }

    /** Conservative bounds covering where the body is now and where it will be after {@code dt}. */
    private static Aabb sweptBounds(RigidBody body, double dt) {
        double r = body.halfExtents.length();
        double ex = body.pos.x + body.linVel.x * dt;
        double ey = body.pos.y + body.linVel.y * dt;
        double ez = body.pos.z + body.linVel.z * dt;

        return new Aabb(
                Math.min(body.pos.x, ex) - r, Math.min(body.pos.y, ey) - r, Math.min(body.pos.z, ez) - r,
                Math.max(body.pos.x, ex) + r, Math.max(body.pos.y, ey) + r, Math.max(body.pos.z, ez) + r);
    }

    /** Eight corners plus six face centres. Enough coverage for 1-block world geometry. */
    private static final int SAMPLE_COUNT = 14;

    private static void samplePoint(RigidBody body, int index, Vector3d out) {
        double hx = body.halfExtents.x;
        double hy = body.halfExtents.y;
        double hz = body.halfExtents.z;

        if (index < 8) {
            out.set((index & 1) == 0 ? -hx : hx,
                    (index & 2) == 0 ? -hy : hy,
                    (index & 4) == 0 ? -hz : hz);
            return;
        }

        switch (index - 8) {
            case 0 -> out.set(-hx, 0.0D, 0.0D);
            case 1 -> out.set(hx, 0.0D, 0.0D);
            case 2 -> out.set(0.0D, -hy, 0.0D);
            case 3 -> out.set(0.0D, hy, 0.0D);
            case 4 -> out.set(0.0D, 0.0D, -hz);
            default -> out.set(0.0D, 0.0D, hz);
        }
    }
}
