package com.pyrostella.corpselab.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 活的只读查询视图，应在实体所属线程使用；部件列表和记录均为脱离求解器的快照 */
public interface CorpseView {
    Entity entity();
    Kind kind();
    ResourceLocation sourceType();
    Optional<UUID> sourceId();
    Optional<UUID> ownerId();
    int ageTicks();
    State state();
    boolean supportsRevival();
    boolean supportsLooting();
    /** 服务端当前姿态；客户端未同步的查询返回空列表，不创建模拟器 */
    List<Part> parts();
    /** 无可用部件时为 -1 */
    int basePartIndex();
    /** 抓取关系和精确复活进度暂仅服务端可查询 */
    Optional<Grab> grab();
    Optional<Revival> revival();

    enum Kind { PLAYER, MOB }
    /** 客户端没有同步的模拟状态用 UNKNOWN 表示 */
    enum State { ACTIVE, FROZEN, GRABBED, REVIVING, REMOVED, UNKNOWN }
    record Rotation(double x, double y, double z, double w) { }
    /** 坐标为世界坐标，半尺寸单位为格；部件 ID 在同一骨架中稳定 */
    record Part(int index, String id, Vec3 position, Rotation rotation, Vec3 halfExtents, float scale) { }
    record Grab(UUID playerId, int partIndex) { }
    record Revival(int elapsedTicks, int durationTicks) {
        public double progress() { return (double) elapsedTicks / durationTicks; }
    }
}
