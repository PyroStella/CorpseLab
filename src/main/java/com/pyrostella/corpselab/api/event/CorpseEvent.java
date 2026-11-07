package com.pyrostella.corpselab.api.event;

import com.pyrostella.corpselab.api.CorpseView;
import com.pyrostella.corpselab.api.Corpses;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.eventbus.api.Event;

/** 仅在逻辑服务端主线程发布到 MinecraftForge.EVENT_BUS */
public abstract class CorpseEvent extends Event {
    private final CorpseView corpse;
    protected CorpseEvent(Entity entity) { this.corpse = Corpses.find(entity).orElseThrow(); }
    public CorpseView getCorpse() { return corpse; }
}
