package com.pyrostella.corpselab;

import com.pyrostella.corpselab.entity.CorpseEntity;
import com.pyrostella.corpselab.entity.MobCorpseEntity;
import com.pyrostella.corpselab.entity.PlanCorpseEntity;
import com.pyrostella.corpselab.entity.RagdollEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class CorpseLabRegistry {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, CorpseLab.MOD_ID);

    public static final RegistryObject<EntityType<RagdollEntity>> RAGDOLL =
            ENTITY_TYPES.register("ragdoll", () -> EntityType.Builder
                    .<RagdollEntity>of(RagdollEntity::new, MobCategory.MISC)
                    // The entity itself is just an anchor; the limbs are drawn from the
                    // synced pose, so its own box only needs to exist for tracking.
                    .sized(0.6F, 0.6F)
                    .clientTrackingRange(10)
                    // Poses change every tick, so the tracker must send every tick.
                    .updateInterval(1)
                    .noSummon()
                    .fireImmune()
                    .build("ragdoll"));

    /**
     * A corpse is tracked further out and persists, unlike a living ragdoll: a player
     * walking back to where they died must be able to see their body before reaching it.
     */
    public static final RegistryObject<EntityType<CorpseEntity>> CORPSE =
            ENTITY_TYPES.register("corpse", () -> EntityType.Builder
                    .<CorpseEntity>of(CorpseEntity::new, MobCategory.MISC)
                    .sized(0.6F, 0.6F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .noSummon()
                    .fireImmune()
                    .build("corpse"));

    /** A dead mob's body. Like a player corpse but carrying nothing and on a timer. */
    public static final RegistryObject<EntityType<MobCorpseEntity>> MOB_CORPSE =
            ENTITY_TYPES.register("mob_corpse", () -> EntityType.Builder
                    .<MobCorpseEntity>of(MobCorpseEntity::new, MobCategory.MISC)
                    .sized(0.6F, 0.6F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .noSummon()
                    .fireImmune()
                    .build("mob_corpse"));

    /**
     * 通用骨架驱动的尸体：部件数量与拓扑来自客户端上报的 BodyPlan，
     * 原版模型与 geo 模型共用同一个实体。
     */
    public static final RegistryObject<EntityType<PlanCorpseEntity>> PLAN_CORPSE =
            ENTITY_TYPES.register("plan_corpse", () -> EntityType.Builder
                    .<PlanCorpseEntity>of(PlanCorpseEntity::new, MobCategory.MISC)
                    .sized(0.8F, 0.8F)
                    .clientTrackingRange(24)
                    .updateInterval(1)
                    .noSummon()
                    .fireImmune()
                    .build("plan_corpse"));

    private CorpseLabRegistry() {
    }
}
