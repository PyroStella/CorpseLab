package com.pyrostella.corpselab.api.model;

/**
 * 布娃娃的六个部件，与原版玩家模型的每个长方体一一对应
 *
 * <p>取六是正确做法而非简化：原版手臂是一整块没有肘部的长方体
 * 所以拆开只会得到无物可画的身体，每个可渲染部件对应一个刚体
 * 渲染因此成为直接映射
 *
 * <p>尺寸由模型像素按每格 16 像素换算为格
 */
public enum BodyPart {

    /** 8x8x8 像素 */
    HEAD(0.25D, 0.25D, 0.25D, 5.0D),
    /** 8x12x4 像素 */
    TORSO(0.25D, 0.375D, 0.125D, 30.0D),
    /** 4x12x4 像素 */
    ARM_LEFT(0.125D, 0.375D, 0.125D, 4.0D),
    ARM_RIGHT(0.125D, 0.375D, 0.125D, 4.0D),
    /** 4x12x4 像素 */
    LEG_LEFT(0.125D, 0.375D, 0.125D, 10.0D),
    LEG_RIGHT(0.125D, 0.375D, 0.125D, 10.0D);

    public final double halfWidth;
    public final double halfHeight;
    public final double halfDepth;
    public final double mass;

    BodyPart(double halfWidth, double halfHeight, double halfDepth, double mass) {
        this.halfWidth = halfWidth;
        this.halfHeight = halfHeight;
        this.halfDepth = halfDepth;
        this.mass = mass;
    }

    public boolean isArm() {
        return this == ARM_LEFT || this == ARM_RIGHT;
    }

    public boolean isLeg() {
        return this == LEG_LEFT || this == LEG_RIGHT;
    }
}
