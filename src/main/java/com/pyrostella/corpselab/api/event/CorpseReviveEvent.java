package com.pyrostella.corpselab.api.event;

import com.pyrostella.corpselab.api.RevivalOptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.Cancelable;
import java.util.Objects;
import java.util.UUID;

/** Start 后每次复活只有一个终结通知；卸载暂停，读档续播不再次触发 Start */
public abstract class CorpseReviveEvent extends CorpseEvent {
    private final UUID attemptId;
    protected CorpseReviveEvent(Entity corpse,UUID attemptId) { super(corpse); this.attemptId=attemptId; }
    public UUID getAttemptId() { return attemptId; }
    /** 条件已初步满足，但还未释放抓取或进入复活；可调整选项 */
    @Cancelable
    public static final class Pre extends CorpseReviveEvent {
        private RevivalOptions options;
        public Pre(Entity corpse,UUID id,RevivalOptions options) { super(corpse,id); this.options=options; }
        public RevivalOptions getOptions() { return options; }
        public void setOptions(RevivalOptions options) { this.options=Objects.requireNonNull(options); }
    }
    public static final class Start extends CorpseReviveEvent {
        private final int durationTicks;
        public Start(Entity corpse,UUID id,int durationTicks) { super(corpse,id); this.durationTicks=durationTicks; }
        public int getDurationTicks() { return durationTicks; }
    }
    /** 每服务端 tick 推进并应用姿态后触发，结束交接之前触发；打断请使用 stopRevival */
    public static final class Tick extends CorpseReviveEvent {
        private final int elapsedTicks,durationTicks;
        public Tick(Entity corpse,UUID id,int elapsedTicks,int durationTicks) {
            super(corpse,id); this.elapsedTicks=elapsedTicks; this.durationTicks=durationTicks;
        }
        public int getElapsedTicks() { return elapsedTicks; }
        public int getDurationTicks() { return durationTicks; }
        public double getProgress() { return (double)elapsedTicks/durationTicks; }
    }
    /** 原生物与所有乘客都已成功入场，尸体已移除 */
    public static final class Complete extends CorpseReviveEvent {
        private final LivingEntity revived;
        public Complete(Entity corpse,UUID id,LivingEntity revived) { super(corpse,id); this.revived=revived; }
        public LivingEntity getRevived() { return revived; }
    }
    public enum Interruption { STOPPED, REMOVED, INVALID_DATA, UUID_CONFLICT, SPAWN_FAILED }
    public static final class Interrupted extends CorpseReviveEvent {
        private final Interruption reason;
        public Interrupted(Entity corpse,UUID id,Interruption reason) { super(corpse,id); this.reason=reason; }
        public Interruption getReason() { return reason; }
    }
}
