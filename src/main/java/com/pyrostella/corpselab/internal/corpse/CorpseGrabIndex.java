package com.pyrostella.corpselab.internal.corpse;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/** 仅索引同一维度的抓取关系，弱引用不延长旧世界和尸体的生命周期 */
public final class CorpseGrabIndex {
    private static final Map<ServerLevel, Map<UUID, WeakReference<Entity>>> GRABS = new WeakHashMap<>();

    private CorpseGrabIndex() { }

    public static Optional<Entity> find(Player player) {
        if (player == null || !(player.level instanceof ServerLevel level)
                || !level.getServer().isSameThread()) return Optional.empty();
        Map<UUID, WeakReference<Entity>> held = GRABS.get(level);
        if (held == null) return Optional.empty();
        WeakReference<Entity> reference = held.get(player.getUUID());
        Entity corpse = reference == null ? null : reference.get();
        if (corpse != null && !corpse.isRemoved() && corpse.level == level
                && corpse instanceof GrabbableCorpse body && body.isGrabbedBy(player)) {
            return Optional.of(corpse);
        }
        held.remove(player.getUUID());
        if (held.isEmpty()) GRABS.remove(level);
        return Optional.empty();
    }

    static void track(Player player, Entity corpse) {
        ServerLevel level = (ServerLevel) corpse.level;
        GRABS.computeIfAbsent(level, ignored -> new HashMap<>())
                .put(player.getUUID(), new WeakReference<>(corpse));
    }

    static void release(Entity corpse, UUID player) {
        if (!(corpse.level instanceof ServerLevel level)) return;
        Map<UUID, WeakReference<Entity>> held = GRABS.get(level);
        if (held == null) return;
        WeakReference<Entity> reference = held.get(player);
        // 旧尸体的延迟释放不得删除同一玩家后来建立的新关系。
        if (reference != null && reference.get() == corpse) held.remove(player);
        if (held.isEmpty()) GRABS.remove(level);
    }
}
