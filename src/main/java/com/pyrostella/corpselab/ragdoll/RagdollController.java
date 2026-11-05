package com.pyrostella.corpselab.ragdoll;

import com.pyrostella.corpselab.config.CorpseLabConfig;
import com.pyrostella.corpselab.entity.RagdollEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 判断玩家是否可以倒下，并让他倒下
 *
 * <p>每个触发器都经由这里，因此冷却与资格规则只在
 * 一处执行，不必在每个触发器里重复
 */
public final class RagdollController {

    private static final Map<UUID, Long> LAST_TUMBLE = new HashMap<>();

    private RagdollController() {
    }

    public static boolean tryManualCorpseLab(ServerPlayer player) {
        if (!CorpseLabConfig.SERVER.allowManualTrigger.get()) {
            return false;
        }
        // 这是玩家主动的，所以抑制列表不生效：主动要求躺下的玩家可以做
        return tumble(player, Vec3.ZERO);
    }

    /**
     * 作为对玩家身上所发生之事的反应把他掀倒
     *
     * <p>与按键不同，这里遵守抑制列表，因此激流冲刺和黏液块弹跳
     * 不会被当成撞墙
     */
    public static boolean tumbleFromTrigger(ServerPlayer player, Vec3 launch) {
        if (Suppressions.isSuppressed(player)) {
            return false;
        }
        return tumble(player, launch);
    }

    /** 玩家符合条件时开始一具布娃娃，返回是否真的开始 */
    public static boolean tumble(ServerPlayer player, Vec3 launch) {
        if (!canCorpseLab(player)) {
            return false;
        }

        LAST_TUMBLE.put(player.getUUID(), player.level().getGameTime());

        RagdollEntity ragdoll = RagdollEntity.spawnFor(player);
        if (launch.lengthSqr() > 0.0D) {
            ragdoll.launch(launch);
        }

        if (CorpseLabConfig.SERVER.soundEnabled.get()) {
            // 使用原版音效，这样模组不带任何音频资源，也无需注册音效
            player.level().playSound(null, ragdoll.getX(), ragdoll.getY(), ragdoll.getZ(),
                    SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS,
                    (float) (double) CorpseLabConfig.SERVER.soundVolume.get(), 0.9F);
        }
        return true;
    }

    public static boolean canCorpseLab(ServerPlayer player) {
        if (!CorpseLabConfig.SERVER.enabled.get()) {
            return false;
        }
        if (player.isSpectator()) {
            return false;
        }
        if (player.isCreative() && !CorpseLabConfig.SERVER.affectCreative.get()) {
            return false;
        }
        // 已经倒着，或者正骑着别的东西
        if (player.getVehicle() != null) {
            return false;
        }
        return offCooldown(player);
    }

    private static boolean offCooldown(ServerPlayer player) {
        Long last = LAST_TUMBLE.get(player.getUUID());
        if (last == null) {
            return true;
        }
        long elapsed = player.level().getGameTime() - last;
        return elapsed >= CorpseLabConfig.SERVER.cooldownTicks.get();
    }

    public static void forget(UUID playerId) {
        LAST_TUMBLE.remove(playerId);
        Suppressions.forget(playerId);
        CorpseLabTriggers.forget(playerId);
    }
}
