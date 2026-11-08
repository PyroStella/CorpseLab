package com.pyrostella.corpselab.api.event;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.Cancelable;
import java.util.Optional;
import java.util.UUID;

public abstract class CorpseGrabEvent extends CorpseEvent {
    private final UUID playerId;
    private final int partIndex;
    protected CorpseGrabEvent(Entity corpse,UUID playerId,int partIndex) {
        super(corpse); this.playerId=playerId; this.partIndex=partIndex;
    }
    public UUID getPlayerId() { return playerId; }
    public int getPartIndex() { return partIndex; }
    /** 离线放手时玩家可能已不存在，仍可通过 UUID 清理附属状态 */
    public Optional<Player> getPlayer() {
        return Optional.ofNullable(getCorpse().entity().level.getPlayerByUUID(playerId));
    }
    @Cancelable
    public static final class Pre extends CorpseGrabEvent {
        private final Player player;
        public Pre(Entity corpse,Player player,int part) { super(corpse,player.getUUID(),part); this.player=player; }
        public Player getGrabber() { return player; }
    }
    public static final class Start extends CorpseGrabEvent {
        public Start(Entity corpse,UUID player,int part) { super(corpse,player,part); }
    }
    /** 每次服务端抓取施力前触发，可调整本 tick 的牵引目标与拉力 */
    public static final class Tick extends CorpseGrabEvent {
        private Vec3 target;
        private double strength;
        public Tick(Entity corpse,UUID player,int part,Vec3 target,double strength) {
            super(corpse,player,part); setTarget(target); setStrength(strength);
        }
        public Vec3 getTarget() { return target; }
        public void setTarget(Vec3 target) {
            if (target==null || !Double.isFinite(target.lengthSqr())) throw new IllegalArgumentException("抓取目标无效");
            this.target=target;
        }
        public double getStrength() { return strength; }
        public void setStrength(double strength) {
            if (!Double.isFinite(strength) || strength<0) throw new IllegalArgumentException("抓取拉力无效");
            this.strength=strength;
        }
    }
    public enum ReleaseReason { RELEASED, DISTANCE, PLAYER_UNAVAILABLE, DISABLED, INVALID_PART, REVIVAL, REMOVED, UNLOADED }
    /** 先清除抓取关系再通知，重复释放不会重复触发 */
    public static final class Release extends CorpseGrabEvent {
        private final ReleaseReason reason;
        public Release(Entity corpse,UUID player,int part,ReleaseReason reason) { super(corpse,player,part); this.reason=reason; }
        public ReleaseReason getReason() { return reason; }
    }
}
