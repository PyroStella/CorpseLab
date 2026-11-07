package com.pyrostella.corpselab.api;

import java.util.Objects;

/** 不指定的选项使用原有行为；不可变，可安全复用 */
public record RevivalOptions(int durationTicks, String basePart, RevivalTarget target) {
    public RevivalOptions {
        if (durationTicks < 1) throw new IllegalArgumentException("复活时长必须为正数");
        Objects.requireNonNull(target);
    }
    public static RevivalOptions defaults() {
        return new RevivalOptions(40, null, (corpse, skeleton) -> RevivalPose.rest(skeleton));
    }
    public RevivalOptions duration(int ticks) { return new RevivalOptions(ticks, basePart, target); }
    public RevivalOptions base(String part) { return new RevivalOptions(durationTicks, Objects.requireNonNull(part), target); }
    public RevivalOptions pose(RevivalPose pose) { return target((corpse, skeleton) -> pose); }
    public RevivalOptions target(RevivalTarget provider) { return new RevivalOptions(durationTicks, basePart, provider); }
}
