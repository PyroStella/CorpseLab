package com.pyrostella.corpselab.internal.skeleton;

import com.pyrostella.corpselab.api.model.BodyPart;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 原版人形模型的几何结构，以及由它到世界空间的换算
 *
 * <p>这里的一切都从模型推导而来，而非目测：当初目测把左右搞反了
 * 原版绘制人形的方式是 {@code rotateY(180 - yaw)}，再接着是
 * {@code scale(-1, -1, 1)}，最后抬升 1.5 格。那个缩放并不是镜像
 * 而是绕 Z 轴旋转 180 度，所以模型 x = -5 的右臂应位于世界坐标
 * +0.3125，而不是 -0.3125
 *
 * <p>因为翻转是一次旋转，把模型空间中的旋转换算到身体空间就是
 * 与它做一次共轭，结果是 X 和 Y 分量取反，Z 保持不变
 */
public final class HumanoidGeometry {

    /** 每格的模型像素数 */
    private static final double PIXEL = 1.0D / 16.0D;

    /** 模型原点位于脚部上方此高度处 */
    private static final double LIFT = 1.5D;

    private HumanoidGeometry() {
    }

    /** 部件的枢轴位置，单位为相对模型原点的模型像素 */
    public static Vector3d pivot(BodyPart part) {
        return switch (part) {
            case HEAD, TORSO -> new Vector3d(0.0D, 0.0D, 0.0D);
            case ARM_RIGHT -> new Vector3d(-5.0D, 2.0D, 0.0D);
            case ARM_LEFT -> new Vector3d(5.0D, 2.0D, 0.0D);
            case LEG_RIGHT -> new Vector3d(-1.9D, 12.0D, 0.0D);
            case LEG_LEFT -> new Vector3d(1.9D, 12.0D, 0.0D);
        };
    }

    /** 部件长方体的中心，单位为相对该部件自身枢轴的模型像素 */
    public static Vector3d cubeCentre(BodyPart part) {
        return switch (part) {
            case HEAD -> new Vector3d(0.0D, -4.0D, 0.0D);
            case TORSO -> new Vector3d(0.0D, 6.0D, 0.0D);
            case ARM_RIGHT -> new Vector3d(-1.0D, 4.0D, 0.0D);
            case ARM_LEFT -> new Vector3d(1.0D, 4.0D, 0.0D);
            case LEG_RIGHT, LEG_LEFT -> new Vector3d(0.0D, 6.0D, 0.0D);
        };
    }

    /**
     * 部件连接到父部件处的模型坐标点：头是脖子，手臂是肩
     * 腿是胯。以模型原点为基准表示，模型原点同时也是
     * 躯干的枢轴
     */
    public static Vector3d attachment(BodyPart part) {
        return switch (part) {
            case HEAD -> new Vector3d(0.0D, 0.0D, 0.0D);
            case TORSO -> new Vector3d(0.0D, 0.0D, 0.0D);
            default -> pivot(part);
        };
    }

    /**
     * 把模型空间中的点（单位为相对模型原点的像素）换算成
     * 相对于人物脚部的偏移，此时尚未应用身体自身的 yaw
     */
    public static Vector3d toBodySpace(Vector3d modelPixels, Vector3d dest) {
        return dest.set(-modelPixels.x * PIXEL, -modelPixels.y * PIXEL + LIFT, modelPixels.z * PIXEL);
    }

    /**
     * 由部件在模型空间中的旋转求它在身体空间中的旋转
     *
     * <p>原版按 Z、Y、X 的顺序应用模型旋转。用翻转对它做共轭
     * 会使 X 和 Y 取反、Z 不变，所以下面的符号并不是笔误
     */
    public static Quaterniond toBodySpace(double xRot, double yRot, double zRot, Quaterniond dest) {
        return dest.identity().rotateZ(zRot).rotateY(-yRot).rotateX(-xRot);
    }

    /**
     * 给定姿态下从脚部到部件长方体中心的偏移，尚未应用身体 yaw
     */
    public static Vector3d restOffset(BodyPart part, LimbPose pose, Vector3d dest) {
        Vector3d centre = new Vector3d(cubeCentre(part));

        // 长方体绕自身枢轴摆动，所以先旋转再平移
        modelRotation(part, pose).transform(centre);
        centre.add(pivot(part)).add(pose.pivotShift(part));

        return toBodySpace(centre, dest);
    }

    /** 由姿态得到部件在模型空间中的旋转 */
    public static Quaterniond modelRotation(BodyPart part, LimbPose pose) {
        return new Quaterniond()
                .rotateZ(0.0D)
                .rotateY(pose.yRot(part))
                .rotateX(pose.xRot(part));
    }

    /** 由姿态得到部件在身体空间中的朝向 */
    public static Quaterniond bodyRotation(BodyPart part, LimbPose pose, Quaterniond dest) {
        return toBodySpace(pose.xRot(part), pose.yRot(part), 0.0D, dest);
    }

    /**
     * 把模型空间中的点表示为部件自身的局部坐标，关节锚点正需要这种形式
     * 它与部件自身的旋转无关，因为两者始终一起运动
     *
     * @param modelPoint 模型像素下的点，相对模型原点
     */
    public static Vector3d anchorIn(BodyPart part, Vector3d modelPoint, LimbPose pose, Vector3d dest) {
        Vector3d relative = new Vector3d(modelPoint)
                .sub(pivot(part))
                .sub(pose.pivotShift(part))
                .sub(cubeCentre(part));

        // 先撤销部件自身的模型旋转，再翻转到部件的局部坐标系
        modelRotation(part, pose).transformInverse(relative);
        return dest.set(-relative.x * PIXEL, -relative.y * PIXEL, relative.z * PIXEL);
    }
}
