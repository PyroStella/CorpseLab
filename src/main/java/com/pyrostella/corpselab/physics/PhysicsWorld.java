package com.pyrostella.corpselab.physics;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 子步化的 XPBD 求解器
 *
 * <p>接触每步只按一个小余量生成一次，之后每个子步重新求值
 * 是标准做法：宽相检测才是开销大头，而余量覆盖了刚体一步之内
 * 可能移动的距离</p>
 */
public final class PhysicsWorld {

    /** Minecraft 的重力，单位为格每二次方秒（20 tps 时相当于 0.08 格每 tick²） */
    public static final double MC_GRAVITY = -32.0D;

    /** 采样点与实体方块的距离在此以内时，接触会保持存活 */
    private static final double CONTACT_MARGIN = 0.04D;

    /** 支撑中的刚体每 tick 保留的速度比例，见 {@link #step} 末尾 */
    private static final double REST_DAMPING = 0.85D;

    /** 速度的硬上限，保证一步之内没有任何东西能跑出接触余量 */
    public double maxSpeed = 128.0D;

    public final List<RigidBody> bodies = new ArrayList<>();
    public final List<Constraint> constraints = new ArrayList<>();

    private final List<Contact> contacts = new ArrayList<>();
    private final List<Aabb> solidScratch = new ArrayList<>();

    public final Vector3d gravity = new Vector3d(0.0D, MC_GRAVITY, 0.0D);
    public WorldCollider collider = WorldCollider.EMPTY;

    /**
     * 用更多子步换取稳定性的代价远低于增加求解迭代次数——这正是小步长 XPBD
     * 那篇论文的核心结论
     */
    public int substeps = 16;

    /**
     * 每个子步把位置约束投影几遍
     *
     * <p>只投一遍时，互相矛盾的硬约束（髋部锚点必须重合 vs 脚不能进地面）只能满足后者
     * 前者留下残余位移，再被 {@link RigidBody#deriveVelocity} 折算成速度：姿态永远在抖
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
     * 一 tick 结束时收尾：记录是否有东西踩在地上，并把支撑中的刚体速度压一点
     *
     * <p>静摩擦在位置层面被多遍迭代摊薄之后，落地尸体仍会以几厘米每秒的速度慢慢滑走
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
     * 截至上一步，装配体里是否有东西停在一个表面上
     *
     * <p>判断直接来自接触本身，而不是高度或速度，所以卡在墙边、或正从
     * 边缘掉下去的刚体不会被误判成已经落地</p>
     */
    public boolean isGrounded() {
        return this.grounded;
    }

    /** 装配体里最高的线速度，用来判断布娃娃是否已经静止 */
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
     * 构建本步的接触集
     *
     * <p>接触是<em>推测性</em>的：只要某个采样点本步内可能够到实体
     * 方块，就为它建一个接触，包括还在半空中的点。在子步的深度
     * 测试判定该点确实已经陷入之前，它一直是不生效的</p>
     *
     * <p>正是这一点阻止了以 128 格每秒（单个 tick 走 6.4 格）发射的刚体直接
     * 穿过一格厚的地板：探测距离就是刚体本步自己的行程，所以它跑得
     * 越快，往前看得越远</p>
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

    /** 点到盒的距离，点在里面时为 0 */
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
     * 为每个接触选定一次逃逸方向，然后在整个步长内保持不变
     *
     * <p>点在盒外时，法线取分离量占优的那个轴——对正落向地板的刚体
     * 来说就是顶面，也就是它将落在的那一面。点在盒内时，取实际
     * 暴露出来的最浅的那个面</p>
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

        // 点在盒内时：从实际暴露出来的最浅的那个面逃逸
        //
        // 在体素世界里，跳过被埋住的面非常关键。一个停在略低于地板表面
        // 的点，离方块竖直侧面的距离远小于离顶面的距离，所以朴素的
        // 最小穿透答案会指向侧向——而相邻两块地板随后会把刚体朝相反
        // 方向推，在游戏里看起来就是已经停稳的布娃娃一直在地面上
        // 慢慢滑行，而且永远停不下来
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

    /** 另一个实体方块紧贴着这个面、使其成为内部几何时为 true */
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

    /** 保守包围盒，同时覆盖刚体现在的位置和经过 {@code dt} 之后的位置 */
    private static Aabb sweptBounds(RigidBody body, double dt) {
        double r = body.halfExtents.length();
        double ex = body.pos.x + body.linVel.x * dt;
        double ey = body.pos.y + body.linVel.y * dt;
        double ez = body.pos.z + body.linVel.z * dt;

        return new Aabb(
                Math.min(body.pos.x, ex) - r, Math.min(body.pos.y, ey) - r, Math.min(body.pos.z, ez) - r,
                Math.max(body.pos.x, ex) + r, Math.max(body.pos.y, ey) + r, Math.max(body.pos.z, ez) + r);
    }

    /** 八个角点加六个面心。对一格大小的世界几何体来说覆盖足够 */
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
