package com.pyrostella.corpselab.internal.corpse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.pyrostella.corpselab.api.CorpseView;
import com.pyrostella.corpselab.api.event.CorpseGrabEvent;
import com.pyrostella.corpselab.api.event.CorpseGrabEvent.ReleaseReason;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import com.pyrostella.corpselab.internal.config.CorpseLabConfig;
import com.pyrostella.corpselab.physics.RigidBody;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** 玩家尸体和通用生物尸体共用的抓取状态与施力逻辑 */
final class CorpseGrabState {

    /** 小型肢体的最小选取半径，单位为方块 */
    private static final double MIN_PICK_RADIUS = 0.6D;

    private final Entity corpse;
    private boolean selecting;
    CorpseGrabState(Entity corpse) { this.corpse=corpse; }
    private UUID grabberId;
    private int grabbedPart = -1;

    /** 依据服务端所见的玩家视线选择最近的刚体 */
    boolean tryGrab(Player grabber, List<RigidBody> bodies) {
        if (selecting || isGrabbed() || bodies.isEmpty() || !validPlayer(grabber)
                || !CorpseLabConfig.SERVER.grabEnabled.get() || playerBusy(grabber)) {
            return false;
        }

        Vec3 eye = grabber.getEyePosition();
        Vec3 look = grabber.getLookAngle();
        double reach = CorpseLabConfig.SERVER.grabReach.get();

        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < bodies.size(); i++) {
            RigidBody candidate = bodies.get(i);
            Vector3d pos = candidate.pos;
            Vec3 toPart = new Vec3(pos.x, pos.y, pos.z).subtract(eye);

            double along = toPart.dot(look);
            double radius = Math.max(MIN_PICK_RADIUS, candidate.halfExtents.length());
            if (along < -radius || along > reach + radius) {
                continue;
            }

            double offAxis = toPart.subtract(look.scale(along)).length();
            if (offAxis > radius) {
                continue;
            }

            // 用视线与包围球的首次交点比较远近，大型部件按表面而不是中心计算距离
            double surfaceDistance = Math.max(0.0D,
                    along - Math.sqrt(radius * radius - offAxis * offAxis));
            if (surfaceDistance > reach || surfaceDistance >= bestDistance) {
                continue;
            }
            bestDistance = surfaceDistance;
            best = i;
        }

        if (best < 0) {
            return false;
        }

        selecting=true;
        try {
            if (MinecraftForge.EVENT_BUS.post(new CorpseGrabEvent.Pre(corpse,grabber,best))) return false;
            if (!validPlayer(grabber) || isGrabbed() || playerBusy(grabber)
                    || !CorpseLabConfig.SERVER.grabEnabled.get()) return false;
            this.grabberId = grabber.getUUID();
            this.grabbedPart = best;
            CorpseGrabIndex.track(grabber, corpse);
            MinecraftForge.EVENT_BUS.post(new CorpseGrabEvent.Start(corpse,grabberId,grabbedPart));
            return true;
        } finally { selecting=false; }
    }

    private boolean validPlayer(Player player) {
        return player!=null && corpse.level instanceof ServerLevel level && level.getServer().isSameThread()
                && !corpse.isRemoved() && player.level==level && player.isAlive() && !player.isSpectator();
    }
    private boolean playerBusy(Player player) {
        return CorpseGrabIndex.find(player).isPresent();
    }
    void release() { release(ReleaseReason.RELEASED); }
    void release(ReleaseReason reason) {
        if (!isGrabbed()) return;
        UUID player=grabberId; int part=grabbedPart;
        CorpseGrabIndex.release(corpse, player);
        this.grabberId = null;
        this.grabbedPart = -1;
        if (!corpse.level.isClientSide) MinecraftForge.EVENT_BUS.post(new CorpseGrabEvent.Release(corpse,player,part,reason));
    }

    boolean isGrabbed() {
        return this.grabberId != null && this.grabbedPart >= 0;
    }

    Optional<CorpseView.Grab> snapshot() {
        return isGrabbed() ? Optional.of(new CorpseView.Grab(grabberId, grabbedPart)) : java.util.Optional.empty();
    }

    boolean isGrabbedBy(Player player) {
        return this.grabberId != null && this.grabberId.equals(player.getUUID());
    }

    /** 把被抓住的刚体拉向玩家视线前方，同时保留世界碰撞 */
    void apply(Level level, List<RigidBody> bodies) {
        if (!isGrabbed()) {
            return;
        }
        if (!CorpseLabConfig.SERVER.grabEnabled.get()) {
            release(ReleaseReason.DISABLED);
            return;
        }
        if (this.grabbedPart >= bodies.size()) {
            release(ReleaseReason.INVALID_PART);
            return;
        }

        Player grabber = level.getPlayerByUUID(this.grabberId);
        if (grabber == null || !grabber.isAlive() || grabber.isSpectator()) {
            release(ReleaseReason.PLAYER_UNAVAILABLE);
            return;
        }

        Vec3 hand = grabber.getEyePosition().add(grabber.getLookAngle().scale(CorpseLabConfig.SERVER.grabHoldDistance.get()));
        RigidBody body = bodies.get(this.grabbedPart);
        Vec3 delta = hand.subtract(body.pos.x, body.pos.y, body.pos.z);

        if (delta.length() > CorpseLabConfig.SERVER.grabBreakDistance.get()) {
            release(ReleaseReason.DISTANCE);
            return;
        }

        UUID playerId=grabberId; int part=grabbedPart;
        var event=new CorpseGrabEvent.Tick(corpse,playerId,part,hand,CorpseLabConfig.SERVER.grabStrength.get());
        MinecraftForge.EVENT_BUS.post(event);
        if (!isGrabbed() || !playerId.equals(grabberId) || part!=grabbedPart || corpse.isRemoved()) return;
        delta=event.getTarget().subtract(body.pos.x,body.pos.y,body.pos.z);
        if (delta.length()>CorpseLabConfig.SERVER.grabBreakDistance.get()) { release(ReleaseReason.DISTANCE); return; }
        double strength=event.getStrength();
        body.linVel.set(delta.x * strength, delta.y * strength, delta.z * strength);
    }
}
