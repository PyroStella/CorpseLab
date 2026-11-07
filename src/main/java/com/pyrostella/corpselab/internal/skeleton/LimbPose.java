package com.pyrostella.corpselab.internal.skeleton;

import com.pyrostella.corpselab.api.model.BodyPart;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3d;

/**
 * 玩家倒下那一刻其模型实际所处的姿态
 *
 * <p>总是以中立站姿开始的布娃娃，看起来像是换了一个人凭空出现
 * 疾跑时双腿分开、手臂摆动，把这些角度直接归零
     * 会有肉眼可见的跳变。这些量都来自原版人形动画
 * 由服务端已有的数据重新算出，所以身体会从玩家
 * 被绘制的位置精确开始
 *
 * <p>所有角度都以弧度为单位，位于模型空间
 */
public record LimbPose(double headYaw, double headPitch,
                       double rightArmPitch, double leftArmPitch,
                       double rightLegPitch, double leftLegPitch,
                       double bodyPitch, boolean crouching) {

    public static final LimbPose STANDING =
            new LimbPose(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, false);

    /**
     * 复现 {@code HumanoidModel.setupAnim} 中会明显改变站立玩家的部分
     * 包括视线方向、行走循环和下蹲
     */
    public static LimbPose of(LivingEntity entity) {
        // 原版用来驱动四肢的行走循环
        float limbSwing = entity.walkAnimation.position();
        float limbSwingAmount = entity.walkAnimation.speed();

        double rightArm = Math.cos(limbSwing * 0.6662D + Math.PI) * 2.0D * limbSwingAmount * 0.5D;
        double leftArm = Math.cos(limbSwing * 0.6662D) * 2.0D * limbSwingAmount * 0.5D;
        double rightLeg = Math.cos(limbSwing * 0.6662D) * 1.4D * limbSwingAmount;
        double leftLeg = Math.cos(limbSwing * 0.6662D + Math.PI) * 1.4D * limbSwingAmount;

        boolean crouching = entity.isCrouching();
        double bodyPitch = 0.0D;
        if (crouching) {
            bodyPitch = 0.5D;
            rightArm += 0.4D;
            leftArm += 0.4D;
        }

        double headYaw = Math.toRadians(Mth.wrapDegrees(entity.getYHeadRot() - entity.yBodyRot));
        double headPitch = Math.toRadians(entity.getXRot());

        return new LimbPose(headYaw, headPitch, rightArm, leftArm, rightLeg, leftLeg,
                bodyPitch, crouching);
    }

    /** 部件在模型空间中的 X 轴旋转 */
    public double xRot(BodyPart part) {
        return switch (part) {
            case HEAD -> headPitch;
            case TORSO -> bodyPitch;
            case ARM_RIGHT -> rightArmPitch;
            case ARM_LEFT -> leftArmPitch;
            case LEG_RIGHT -> rightLegPitch;
            case LEG_LEFT -> leftLegPitch;
        };
    }

    /** 部件在模型空间中的 Y 轴旋转，只有头会转向 */
    public double yRot(BodyPart part) {
        return part == BodyPart.HEAD ? headYaw : 0.0D;
    }

    /**
     * 下蹲不只是旋转：原版还会平移头部、躯干和腿，这就是潜行玩家
     * 变矮而不只是倾斜的原因
     */
    public Vector3d pivotShift(BodyPart part) {
        if (!crouching) {
            return new Vector3d();
        }
        return switch (part) {
            case HEAD -> new Vector3d(0.0D, 4.2D, 0.0D);
            case TORSO -> new Vector3d(0.0D, 3.2D, 0.0D);
            case ARM_RIGHT, ARM_LEFT -> new Vector3d(0.0D, 3.2D, 0.0D);
            case LEG_RIGHT, LEG_LEFT -> new Vector3d(0.0D, 0.2D, 4.0D);
        };
    }
}
