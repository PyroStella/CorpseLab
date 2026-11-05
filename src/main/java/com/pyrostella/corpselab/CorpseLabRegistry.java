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

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, CorpseLab.MOD_ID);

    public static final RegistryObject<EntityType<RagdollEntity>> RAGDOLL =
            ENTITY_TYPES.register("ragdoll", () -> EntityType.Builder
                    .<RagdollEntity>of(RagdollEntity::new, MobCategory.MISC)
                    // 实体本身只是一个锚点：四肢按同步过来的姿态绘制
                    // 所以它自己的碰撞箱只要存在、能被追踪就够了
                    .sized(0.6F, 0.6F)
                    .clientTrackingRange(10)
                    // 姿态每个 tick 都在变，所以追踪器必须每个 tick 都发包
                    .updateInterval(1)
                    .noSummon()
                    .fireImmune()
                    .build("ragdoll"));

    /**
     * 尸体比活着的布娃娃追踪得更远，而且会持久存在：玩家走回自己
     * 死亡的地点时，必须在走到之前就能看见自己的尸体
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

    /** 死亡生物的尸体。与玩家尸体类似，但身上没有物品，而且有存活计时 */
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
     * 通用骨架驱动的尸体：部件数量与拓扑来自客户端上报的 BodyPlan
     * 原版模型与 geo 模型共用同一个实体
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
