package com.pyrostella.corpselab.internal.corpse;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.api.event.CorpseRemovalEvent;
import com.pyrostella.corpselab.api.event.CorpseRemovalEvent.Cause;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.Map;
import java.util.WeakHashMap;

/** 创建提交状态和退出原因；记录不延长实体及其世界的生命周期 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID)
public final class CorpseLifecycle {
    private static final Map<Entity, State> STATES = new WeakHashMap<>();

    private static final class State {
        boolean creating;
        boolean committed;
        boolean exited;
        Cause cause;
    }

    private CorpseLifecycle() { }

    static void beginCreation(Entity corpse) {
        State state = new State();
        state.creating = true;
        STATES.put(corpse, state);
    }

    static void commitCreation(Entity corpse) {
        State state = STATES.get(corpse);
        state.creating = false;
        state.committed = true;
    }

    static void abortCreation(Entity corpse) {
        State state = STATES.get(corpse);
        if (state != null) {
            state.creating = false;
            state.committed = false;
        }
    }

    static boolean isCommitted(Entity corpse) {
        State state = STATES.get(corpse);
        return corpse.level() instanceof ServerLevel level
                && level.getEntity(corpse.getUUID()) == corpse
                && state != null && state.committed && !state.creating && !state.exited;
    }

    /** 存档加载和维度重入只有入场状态，没有新的创建事件 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoined(EntityJoinLevelEvent event) {
        Entity corpse = event.getEntity();
        if (event.getLevel().isClientSide || !(corpse instanceof CorpseEntity || corpse instanceof PlanCorpseEntity)) return;
        State state = STATES.computeIfAbsent(corpse, ignored -> new State());
        if (!state.creating) {
            state.committed = true;
            state.exited = false;
            state.cause = null;
        }
    }

    static void discard(Entity corpse, Cause cause) {
        State state = STATES.get(corpse);
        if (state != null && !state.exited) state.cause = cause;
        corpse.discard();
    }

    /** Entity.setRemoved 结束后唯一派发，不依赖跟踪可见性或区块卸载顺序。 */
    public static void onEntityRemoved(Entity entity, Entity.RemovalReason reason) {
        if (reason == null) return;
        if (entity instanceof PlanCorpseEntity corpse) corpse.finishRemoval(reason);
        else if (entity instanceof RagdollEntity corpse) corpse.finishRemoval(reason);
    }

    /** 在实体自身的抓取、复活和肢体代理清理完成后调用 */
    static void onRemoved(Entity corpse, Entity.RemovalReason reason) {
        if (!(corpse.level() instanceof ServerLevel)) return;
        State state = STATES.get(corpse);
        if (state == null || !state.committed || state.creating || state.exited) return;
        state.exited = true;
        Cause cause = !reason.shouldDestroy() ? Cause.UNLOADED
                : state.cause == null ? Cause.DISCARDED : state.cause;
        MinecraftForge.EVENT_BUS.post(new CorpseRemovalEvent(corpse, cause, reason));
    }
}
