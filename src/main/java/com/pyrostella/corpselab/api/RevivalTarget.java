package com.pyrostella.corpselab.api;

import com.pyrostella.corpselab.api.model.BodyPlan;

/** 外部动画系统只需将指定时刻的状态转换为部件姿态；复活开始时仅采样一次 */
@FunctionalInterface
public interface RevivalTarget {
    RevivalPose sample(CorpseView corpse, BodyPlan skeleton);
}
