package com.pyrostella.corpselab.internal.corpse;

import net.minecraft.world.entity.player.Player;

/** 可以被玩家抓住并拖动的尸体 */
public interface GrabbableCorpse {

    boolean tryGrab(Player grabber);

    void releaseGrab();

    boolean isGrabbedBy(Player player);

    /** 抓取成功后恢复物理模拟，默认无需额外处理 */
    default void wakeForGrab() {
    }
}
