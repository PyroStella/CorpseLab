package com.pyrostella.corpselab.entity;

import com.pyrostella.corpselab.CorpseLabRegistry;
import com.pyrostella.corpselab.config.CorpseLabConfig;
import com.pyrostella.corpselab.ragdoll.LimbPose;
import com.pyrostella.corpselab.ragdoll.RagdollSkeleton;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Optional;

/**
 * 已经死亡的生物的身体，用来取代原版那个倒下的死亡动画
 *
 * <p>不携带任何掉落物：生物的掉落物是原版的事，仍然会掉在地上
 * 这里纯粹只是尸体，并且会按计时器消失
 *
 * <p>只有类人生物才会拥有尸体。哪些生物算数由服务端依据一份
 * 配置列表决定，因为要画在尸体上的模型是客户端的东西
 * 服务端根本看不到
 */
public class MobCorpseEntity extends SettlingBody {

    /** 这具尸体来自哪种生物，让客户端知道该画谁的模型 */
    private static final EntityDataAccessor<String> SOURCE_TYPE =
            SynchedEntityData.defineId(MobCorpseEntity.class, EntityDataSerializers.STRING);

    /** 幼年生物按半尺寸绘制，它们的尸体也应如此 */
    private static final EntityDataAccessor<Boolean> BABY =
            SynchedEntityData.defineId(MobCorpseEntity.class, EntityDataSerializers.BOOLEAN);

    public MobCorpseEntity(EntityType<? extends MobCorpseEntity> type, Level level) {
        super(type, level);
    }

    /** 在生物刚刚死亡的位置创建尸体，并带上它当时的姿态与运动状态 */
    public static MobCorpseEntity of(LivingEntity mob, Vec3 momentum) {
        Level level = mob.level();
        MobCorpseEntity corpse = new MobCorpseEntity(CorpseLabRegistry.MOB_CORPSE.get(), level);

        Vec3 feet = mob.position();
        corpse.setPos(feet.x, feet.y, feet.z);

        ResourceLocation id = EntityType.getKey(mob.getType());
        corpse.entityData.set(SOURCE_TYPE, id.toString());
        corpse.entityData.set(BABY, mob.isBaby());

        RagdollSkeleton skeleton = new RagdollSkeleton(feet.x, feet.y, feet.z,
                RagdollSkeleton.facingFromYaw(mob.yBodyRot),
                CorpseLabConfig.SERVER.partSelfCollision.get(),
                LimbPose.of(mob));
        skeleton.setCollider(new LevelCollider(level));
        skeleton.world().maxSpeed = CorpseLabConfig.SERVER.maxLaunchSpeed.get();
        skeleton.launch(new Vector3d(momentum.x, momentum.y, momentum.z));

        corpse.setSkeleton(skeleton);
        return corpse;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(SOURCE_TYPE, "");
        entityData.define(BABY, false);
    }

    /** 应当绘制其模型的生物类型，未知时为空 */
    public Optional<EntityType<?>> sourceType() {
        String id = entityData.get(SOURCE_TYPE);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        return EntityType.byString(id);
    }

    public boolean isBaby() {
        return entityData.get(BABY);
    }

    @Override
    protected void checkLifetime() {
        int lifetime = CorpseLabConfig.SERVER.mobCorpseLifetimeTicks.get();
        if (lifetime > 0 && age() >= lifetime) {
            discard();
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setAge(tag.getInt("Age"));
        entityData.set(SOURCE_TYPE, tag.getString("SourceType"));
        entityData.set(BABY, tag.getBoolean("Baby"));

        RagdollSkeleton skeleton = new RagdollSkeleton(getX(), getY(), getZ(), 0.0D,
                false, LimbPose.STANDING);
        skeleton.setCollider(new LevelCollider(level()));
        setSkeleton(skeleton);

        BodyPose.read(tag, skeleton);
        setFrozen(true);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Age", age());
        tag.putString("SourceType", entityData.get(SOURCE_TYPE));
        tag.putBoolean("Baby", isBaby());
        BodyPose.write(tag, skeleton());
    }
}
