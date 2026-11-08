package com.pyrostella.corpselab.client.animation;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import java.util.Arrays;

/** 姿态和缩放在客户端 tick 边界一起提升，避免网络到包时刻改变帧间插值 */
public final class PoseBuffer {
    private final int partCount;
    private Vec3[] pendingPositions, currentPositions, previousPositions;
    private Quaternionf[] pendingRotations, currentRotations, previousRotations;
    private boolean[] pendingRegrowing, currentRegrowing;
    private float[] pendingScales, currentScales, previousScales;
    private final Vec3[] sampledPositions;
    private final Quaternionf[] sampledRotations;
    private final float[] sampledScales;

    public PoseBuffer(int count) {
        partCount = count;
        sampledPositions = new Vec3[count]; sampledRotations = new Quaternionf[count]; sampledScales = new float[count];
        Arrays.fill(sampledPositions,Vec3.ZERO); Arrays.fill(sampledScales,1);
        for (int i = 0; i < count; i++) sampledRotations[i] = new Quaternionf();
    }
    public boolean hasPose() { return currentPositions != null; }
    public int partCount() { return partCount; }
    public void push(Vec3[] positions,Quaternionf[] rotations) {
        float[] scales = new float[partCount]; Arrays.fill(scales,1); push(positions,rotations,scales);
    }
    public void push(Vec3[] positions,Quaternionf[] rotations,float[] scales) {
        boolean[] regrowing = new boolean[partCount];
        for (int i = 0; i < partCount; i++) regrowing[i] = scales[i] < 1;
        push(positions, rotations, scales, regrowing);
    }
    public void push(Vec3[] positions,Quaternionf[] rotations,float[] scales,boolean[] regrowing) {
        if (positions.length != partCount || rotations.length != partCount || scales.length != partCount || regrowing.length != partCount)
            throw new IllegalArgumentException("姿态部件数量不符");
        pendingRegrowing = regrowing.clone();
        pendingPositions = positions.clone(); pendingScales = scales.clone();
        pendingRotations = new Quaternionf[partCount];
        for (int i = 0; i < partCount; i++) pendingRotations[i] = new Quaternionf(rotations[i]);
        if (currentPositions == null) promote();
    }
    public void onClientTick() {
        if (pendingPositions != null) { promote(); return; }
        previousPositions = currentPositions; previousRotations = currentRotations; previousScales = currentScales;
    }
    private void promote() {
        previousPositions = currentPositions; previousRotations = currentRotations; previousScales = currentScales;
        boolean[] previousRegrowing = currentRegrowing;
        currentRegrowing = pendingRegrowing;
        currentPositions = pendingPositions; currentRotations = pendingRotations; currentScales = pendingScales;
        pendingPositions = null; pendingRotations = null; pendingScales = null;
        if (previousPositions == null) {
            previousPositions = currentPositions; previousRotations = currentRotations; previousScales = currentScales;
        }
        previousPositions = previousPositions.clone(); previousRotations = previousRotations.clone(); previousScales = previousScales.clone();
        for (int i = 0; i < partCount; i++) {
            // 第一次进入长回阶段时立即隐藏旧断肢，不把碎块从原位置拖向目标
            if (currentRegrowing[i] && (previousRegrowing == null || !previousRegrowing[i])) {
                previousScales[i] = 0; previousPositions[i] = currentPositions[i]; previousRotations[i] = currentRotations[i];
            }
        }
    }
    public void sample(float partialTick) {
        if (currentPositions == null) return;
        for (int i = 0; i < partCount; i++) {
            sampledPositions[i] = previousPositions[i].lerp(currentPositions[i],partialTick);
            sampledRotations[i].set(previousRotations[i]).slerp(currentRotations[i],partialTick);
            sampledScales[i] = previousScales[i]+(currentScales[i]-previousScales[i])*partialTick;
        }
    }
    public Vec3 position(int index) { return sampledPositions[index]; }
    public Quaternionf rotation(int index,Quaternionf dest) { return dest.set(sampledRotations[index]); }
    public float scale(int index) { return sampledScales[index]; }
}