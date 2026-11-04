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
 * Decides whether a player may go down, and puts them down.
 *
 * <p>Every trigger routes through here, so the cooldown and the eligibility rules are
 * enforced in exactly one place rather than repeated per trigger.
 */
public final class RagdollController {

    private static final Map<UUID, Long> LAST_TUMBLE = new HashMap<>();

    private RagdollController() {
    }

    public static boolean tryManualCorpseLab(ServerPlayer player) {
        if (!CorpseLabConfig.SERVER.allowManualTrigger.get()) {
            return false;
        }
        // Deliberate, so suppression does not apply: a player asking to lie down may.
        return tumble(player, Vec3.ZERO);
    }

    /**
     * Knocks a player down in reaction to something that happened to them.
     *
     * <p>Unlike the keybind, this respects the suppression list, so riptide launches and
     * slime bounces do not read as crashes.
     */
    public static boolean tumbleFromTrigger(ServerPlayer player, Vec3 launch) {
        if (Suppressions.isSuppressed(player)) {
            return false;
        }
        return tumble(player, launch);
    }

    /** Starts a ragdoll if the player is eligible. Returns whether one began. */
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
            // A vanilla sound, so the mod ships no audio assets and needs no sound registry.
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
        // Already down, or riding something else.
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
