package com.pyrostella.corpselab;

import com.pyrostella.corpselab.entity.CorpseEntity;
import com.pyrostella.corpselab.entity.PlanCorpseEntity;
import com.pyrostella.corpselab.entity.CorpseLimbEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class CorpseLabRegistry {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, CorpseLab.MOD_ID);

    /** 玩家走回死亡地点时，必须在到达前看见自己的尸体 */
    public static final RegistryObject<EntityType<CorpseEntity>> CORPSE =
            ENTITY_TYPES.register("corpse", () -> EntityType.Builder
                    .<CorpseEntity>of(CorpseEntity::new, MobCategory.MISC)
                    .sized(0.6F, 0.6F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .noSummon()
                    .fireImmune()
                    .build("corpse"));

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

    /** 肢体不独立存档，随所属尸体重建，客户端继续绘制整具模型 */
    public static final RegistryObject<EntityType<CorpseLimbEntity>> CORPSE_LIMB =
            ENTITY_TYPES.register("corpse_limb", () -> EntityType.Builder
                    .<CorpseLimbEntity>of(CorpseLimbEntity::new, MobCategory.MISC)
                    .sized(0.1F, 0.1F)
                    .clientTrackingRange(0)
                    .noSave()
                    .noSummon()
                    .fireImmune()
                    .build("corpse_limb"));

    private CorpseLabRegistry() {
    }
}
