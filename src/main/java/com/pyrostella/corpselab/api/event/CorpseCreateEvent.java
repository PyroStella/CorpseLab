package com.pyrostella.corpselab.api.event;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.Cancelable;
import java.util.Optional;

/** 首次创建的专用事件，读档与重新跟踪不会触发 */
public abstract class CorpseCreateEvent extends CorpseEvent {
    private final LivingEntity source;
    protected CorpseCreateEvent(Entity corpse, LivingEntity source) { super(corpse); this.source=source; }
    public Optional<LivingEntity> getSource() { return Optional.ofNullable(source); }
    /** 尸体已初始化但尚未入场、移交物品或淘汰旧尸体，取消时保持原流程 */
    @Cancelable
    public static final class Pre extends CorpseCreateEvent {
        public Pre(Entity corpse,LivingEntity source) { super(corpse,source); }
    }
    /** 入场与物品交接成功之后的通知，不能撤销已提交操作 */
    public static final class Post extends CorpseCreateEvent {
        public Post(Entity corpse,LivingEntity source) { super(corpse,source); }
    }
}
