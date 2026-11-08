package com.pyrostella.corpselab.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Quaternion;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 1.19.2 的渲染栈还是 com.mojang.math 的矩阵与四元数，1.20 才换成 JOML
 * <p>
 * 模组内部的位姿统一用 JOML 表示（网络包、物理、动画都是），只在进出
 * {@link PoseStack} 这一层做一次换算，别处的数学代码与高版本保持一致
 */
public final class PoseCompat {

    private PoseCompat() {
    }

    /** 1.19.2 的 {@code PoseStack.mulPose} 只接受 {@link Quaternion} */
    public static void mulPose(PoseStack stack, Quaternionf rotation) {
        stack.mulPose(new Quaternion(rotation.x, rotation.y, rotation.z, rotation.w));
    }

    /**
     * 把 1.19.2 的 {@code com.mojang.math.Matrix4f} 读成 JOML 矩阵
     * <p>
     * {@code store} 按列主序写出（翻译在索引 12），与 JOML 的
     * {@code Matrix4f(FloatBuffer)} 读取顺序一致，不需要转置
     */
    public static Matrix4f toJoml(com.mojang.math.Matrix4f matrix) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(64)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        matrix.store(buffer);
        buffer.rewind();
        return new Matrix4f(buffer);
    }
}
