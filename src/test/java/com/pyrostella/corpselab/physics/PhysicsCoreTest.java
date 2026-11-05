package com.pyrostella.corpselab.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物理核心不依赖 Minecraft，所以验证放在这里，不必重启游戏
 * 每个用例都在毫秒级完成
 */
class PhysicsCoreTest {

    private static final double TICK = 1.0D / 20.0D;

    /** 占据 y 区间 [-1, 0] 的地板，够宽，不会有东西从边上跑掉 */
    private static final Aabb FLOOR = new Aabb(-64.0D, -1.0D, -64.0D, 64.0D, 0.0D, 64.0D);

    private static WorldCollider floor() {
        return (query, out) -> {
            if (query.intersects(FLOOR)) {
                out.add(FLOOR);
            }
        };
    }

    private static PhysicsWorld worldWithFloor() {
        PhysicsWorld world = new PhysicsWorld();
        world.collider = floor();
        return world;
    }

    private static RigidBody box(PhysicsWorld world, double half, double mass, double x, double y, double z) {
        RigidBody body = new RigidBody(new Vector3d(half, half, half), mass);
        body.pos.set(x, y, z);
        world.bodies.add(body);
        return body;
    }

    private static void run(PhysicsWorld world, int ticks) {
        for (int i = 0; i < ticks; i++) {
            world.step(TICK);
        }
    }

    private static void assertFinite(RigidBody body) {
        assertTrue(Double.isFinite(body.pos.x) && Double.isFinite(body.pos.y) && Double.isFinite(body.pos.z),
                "position went non-finite: " + body.pos);
        assertTrue(Double.isFinite(body.rot.x) && Double.isFinite(body.rot.y)
                        && Double.isFinite(body.rot.z) && Double.isFinite(body.rot.w),
                "orientation went non-finite: " + body.rot);
        assertTrue(Double.isFinite(body.linVel.x) && Double.isFinite(body.linVel.y) && Double.isFinite(body.linVel.z),
                "velocity went non-finite: " + body.linVel);
    }

    @Test
    void boxDroppedOnFloorRestsAtItsHalfHeight() {
        PhysicsWorld world = worldWithFloor();
        RigidBody body = box(world, 0.25D, 10.0D, 0.0D, 5.0D, 0.0D);

        run(world, 200);

        assertFinite(body);
        assertEquals(0.25D, body.pos.y, 0.02D, "box should rest with its bottom face on the floor");
        assertTrue(body.linVel.length() < 0.05D, "box should have come to rest, speed was " + body.linVel.length());
    }

    @Test
    void bodyLaunchedAtMaxSpeedDoesNotTunnelThroughTheFloor() {
        PhysicsWorld world = worldWithFloor();
        RigidBody body = box(world, 0.25D, 10.0D, 0.0D, 20.0D, 0.0D);
        // 128 格每秒是配置的发射速度上限：单个 tick 走 6.4 格，远远
        // 超过一格厚的地板
        body.linVel.set(0.0D, -128.0D, 0.0D);

        run(world, 100);

        assertFinite(body);
        assertTrue(body.pos.y > 0.0D,
                "body tunnelled through the floor, ended at y=" + body.pos.y);
        assertEquals(0.25D, body.pos.y, 0.05D, "body should end up resting on the floor");
    }

    @Test
    void jointHoldsItsAnchorsTogether() {
        PhysicsWorld world = new PhysicsWorld();

        RigidBody anchor = new RigidBody(new Vector3d(0.25D, 0.375D, 0.125D), 0.0D);
        anchor.pos.set(0.0D, 4.0D, 0.0D);
        world.bodies.add(anchor);

        RigidBody hanging = new RigidBody(new Vector3d(0.125D, 0.375D, 0.125D), 5.0D);
        hanging.pos.set(0.0D, 3.25D, 0.0D);
        world.bodies.add(hanging);

        // 关节坐标系的 X 轴就是骨骼方向，所以让它指向肢体下方
        Quaterniond down = new Quaterniond().rotateZ(Math.toRadians(-90.0D));

        SphericalJoint joint = new SphericalJoint(
                anchor, new Vector3d(0.0D, -0.375D, 0.0D), down,
                hanging, new Vector3d(0.0D, 0.375D, 0.0D), down);
        joint.limits(60.0D, -30.0D, 30.0D);
        world.constraints.add(joint);

        // 往侧向踹一脚，让关节真正受力，而不是单纯竖直悬挂
        hanging.linVel.set(6.0D, 0.0D, 2.0D);

        double peakGap = 0.0D;
        double steadyGap = 0.0D;
        for (int i = 0; i < 400; i++) {
            world.step(TICK);
            Vector3d a = anchor.toWorld(new Vector3d(0.0D, -0.375D, 0.0D), new Vector3d());
            Vector3d b = hanging.toWorld(new Vector3d(0.0D, 0.375D, 0.0D), new Vector3d());
            double gap = a.distance(b);

            peakGap = Math.max(peakGap, gap);
            if (i >= 20) {
                steadyGap = Math.max(steadyGap, gap);
            }
        }

        assertFinite(hanging);

        // 决定四肢看起来连不连得住的是稳态：实测 0.0011 格，还不到
        // 一个像素的五十分之一，这个阈值是为此设的回归防线，而不是
        // 视觉上的硬要求
        assertTrue(steadyGap < 2.0e-3D, "joint drifted to " + steadyGap + " blocks in steady state");

        // 位置求解是线性化的，所以关节第一次顶到摆动限位的那一 tick 会
        // 短暂过冲，实测峰值约 0.01 格。Minecraft 一个像素是 1/16 格
        // 所以阈值定成连这个尖峰也保持在亚像素级别
        assertTrue(peakGap < 1.0D / 16.0D, "joint separated visibly, peak was " + peakGap + " blocks");
    }

    @Test
    void swingLimitKeepsTheBoneInsideItsCone() {
        PhysicsWorld world = new PhysicsWorld();

        RigidBody anchor = new RigidBody(new Vector3d(0.25D, 0.375D, 0.125D), 0.0D);
        anchor.pos.set(0.0D, 4.0D, 0.0D);
        world.bodies.add(anchor);

        RigidBody limb = new RigidBody(new Vector3d(0.125D, 0.375D, 0.125D), 5.0D);
        limb.pos.set(0.0D, 3.25D, 0.0D);
        world.bodies.add(limb);

        Quaterniond down = new Quaterniond().rotateZ(Math.toRadians(-90.0D));

        SphericalJoint joint = new SphericalJoint(
                anchor, new Vector3d(0.0D, -0.375D, 0.0D), down,
                limb, new Vector3d(0.0D, 0.375D, 0.0D), down);
        joint.limits(45.0D, -30.0D, 30.0D);
        world.constraints.add(joint);

        // 力度足够大，把肢体狠狠顶到限位上并压住
        limb.linVel.set(40.0D, 0.0D, 0.0D);
        limb.angVel.set(0.0D, 0.0D, -30.0D);

        double worstSwing = 0.0D;
        Vector3d boneAxis = new Vector3d(1.0D, 0.0D, 0.0D);
        for (int i = 0; i < 200; i++) {
            world.step(TICK);

            Vector3d a = new Quaterniond(anchor.rot).mul(down).transform(new Vector3d(boneAxis));
            Vector3d b = new Quaterniond(limb.rot).mul(down).transform(new Vector3d(boneAxis));
            worstSwing = Math.max(worstSwing, Math.toDegrees(a.angle(b)));
        }

        assertFinite(limb);
        // 少量过冲是预期的：限位是软约束，每个子步单独求解
        assertTrue(worstSwing < 60.0D,
                "swing reached " + worstSwing + " degrees, well past the 45 degree cone");
    }

    @Test
    void slidingBodyIsSlowedByFriction() {
        PhysicsWorld world = worldWithFloor();
        RigidBody body = box(world, 0.25D, 10.0D, 0.0D, 0.25D, 0.0D);
        body.linVel.set(6.0D, 0.0D, 0.0D);

        run(world, 200);

        assertFinite(body);
        assertTrue(body.linVel.length() < 1.0D,
                "friction should have stopped the box, speed was " + body.linVel.length());
    }

    @Test
    void bodyBuriedInTerrainIsPushedUpNotSideways() {
        // 由一个个方块拼成的地板，真实世界就是这个样子
        PhysicsWorld world = new PhysicsWorld();
        world.collider = (query, out) -> {
            for (int x = (int) Math.floor(query.minX()); x <= (int) Math.floor(query.maxX()); x++) {
                for (int z = (int) Math.floor(query.minZ()); z <= (int) Math.floor(query.maxZ()); z++) {
                    if (query.minY() <= 0.0D && query.maxY() >= -1.0D) {
                        out.add(Aabb.ofBlock(x, -1, z));
                    }
                }
            }
        };

        // 埋在方块里，并且故意贴着两块地板之间的竖直分界
        // 最近的面就是这个侧面，所以朴素的最小穿透逃逸会把刚体
        // 往侧向推进邻居方块里，而不是往上推到开阔的空气里
        RigidBody body = new RigidBody(new Vector3d(0.1D, 0.1D, 0.1D), 5.0D);
        body.pos.set(0.95D, -0.4D, 0.5D);
        world.bodies.add(body);

        run(world, 100);

        assertFinite(body);
        assertTrue(body.pos.y > 0.0D, "buried body never surfaced, ended at y=" + body.pos.y);
        assertEquals(0.95D, body.pos.x, 0.25D,
                "buried body was pushed sideways to x=" + body.pos.x + " instead of straight up");
    }

    @Test
    void longRunStaysFinite() {
        PhysicsWorld world = worldWithFloor();
        List<RigidBody> parts = List.of(
                box(world, 0.25D, 8.0D, 0.0D, 6.0D, 0.0D),
                box(world, 0.2D, 6.0D, 0.3D, 7.0D, 0.1D),
                box(world, 0.15D, 4.0D, -0.2D, 8.0D, -0.1D));

        parts.forEach(b -> b.angVel.set(12.0D, 8.0D, -14.0D));

        run(world, 1000);

        parts.forEach(PhysicsCoreTest::assertFinite);
    }
}
