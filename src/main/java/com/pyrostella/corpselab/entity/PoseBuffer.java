package com.pyrostella.corpselab.entity;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * 保存最近两个布娃娃姿态，并在客户端 tick 时钟上于二者之间插值
 *
 * <p>微妙之处在于新到达的姿态<em>何时</em>成为插值的目标
 * 在数据包到达的瞬间就提升它，是画面撕裂的起因：partial tick
 * 在一个客户端 tick 内从 0 跑到 1，而数据包到达的时刻取决于网络
 * 如果它在一个 tick 进行到 60% 时到达，渲染器就会在一个只剩
 * 40% 时间的间隔上插值 70%，于是身体向前猛冲又弹回
 * 误差与速度成正比，这也是为什么正在停稳的尸体看起来正常
 * 而被抛出去的尸体会闪烁
 *
 * <p>改为在 tick 边界上提升，意味着参与插值的两个姿态始终是
 * 相邻的 tick，而这正是 partial tick 衡量的东西，和原版处理 entity
 * 位置用的是同一套方案，因此尸体、摄像机和 entity 都处在同一个时钟上
 * 一个迟到的包只会多保持一个 tick，而不是造成可见的撕裂
 */
public final class PoseBuffer {

    private final int partCount;

    private Vec3[] pendingPositions;
    private Quaternionf[] pendingRotations;

    private Vec3[] currentPositions;
    private Quaternionf[] currentRotations;
    private Vec3[] previousPositions;
    private Quaternionf[] previousRotations;

    private final Vec3[] sampledPositions;
    private final Quaternionf[] sampledRotations;

    public PoseBuffer(int partCount) {
        this.partCount = partCount;
        this.sampledPositions = new Vec3[partCount];
        this.sampledRotations = new Quaternionf[partCount];

        for (int i = 0; i < partCount; i++) {
            sampledPositions[i] = Vec3.ZERO;
            sampledRotations[i] = new Quaternionf();
        }
    }

    public boolean hasPose() {
        return currentPositions != null;
    }

    /** 记录一份快照。它要到下一个 tick 边界才会变得可见 */
    public void push(Vec3[] positions, Quaternionf[] rotations) {
        Quaternionf[] copied = new Quaternionf[partCount];
        for (int i = 0; i < partCount; i++) {
            copied[i] = new Quaternionf(rotations[i]);
        }
        pendingPositions = positions;
        pendingRotations = copied;

        // 第一份快照没有任何东西可插值，所以立刻显示它
        // 而不是留一帧没有尸体的画面
        if (currentPositions == null) {
            promote();
        }
    }

    /** 把插值推进一个 tick。由 entity 的客户端 tick 调用 */
    public void onClientTick() {
        if (pendingPositions != null) {
            promote();
            return;
        }

        // 没有新数据到达。把插值收拢到当前姿态，让尸体保持不动
        // 留着它就会从头重放上一个 tick 的运动
        // 尸体会肉眼可见地往回倒带
        previousPositions = currentPositions;
        previousRotations = currentRotations;
    }

    private void promote() {
        previousPositions = currentPositions;
        previousRotations = currentRotations;
        currentPositions = pendingPositions;
        currentRotations = pendingRotations;
        pendingPositions = null;
        pendingRotations = null;

        if (previousPositions == null) {
            previousPositions = currentPositions;
            previousRotations = currentRotations;
        }
    }

    /** 解析出这一帧的姿态。在读取任何部位之前调用一次 */
    public void sample(float partialTick) {
        if (currentPositions == null) {
            return;
        }
        for (int i = 0; i < partCount; i++) {
            sampledPositions[i] = previousPositions[i].lerp(currentPositions[i], partialTick);
            sampledRotations[i].set(previousRotations[i]).slerp(currentRotations[i], partialTick);
        }
    }

    public Vec3 position(int index) {
        return sampledPositions[index];
    }

    public Quaternionf rotation(int index, Quaternionf dest) {
        return dest.set(sampledRotations[index]);
    }
}
