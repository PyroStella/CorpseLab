package com.pyrostella.corpselab.internal.corpse;

import com.pyrostella.corpselab.api.CorpseView;
import com.pyrostella.corpselab.api.model.BodyPart;
import com.pyrostella.corpselab.physics.RigidBody;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** 统一只读视图的组装，部件按需生成快照 */
public final class CorpseViews {
    private static final List<String> PLAYER_PART_IDS = Arrays.stream(BodyPart.values())
            .map(part -> part.name().toLowerCase(Locale.ROOT)).toList();
    private CorpseViews() { }

    public static Entity resolve(Entity entity) {
        return entity instanceof CorpseLimbEntity limb ? limb.corpse() : entity;
    }

    public static Optional<CorpseView> find(Entity entity) {
        entity = resolve(entity);
        return entity instanceof CorpseEntity || entity instanceof PlanCorpseEntity
                ? Optional.of(new View(entity)) : Optional.empty();
    }

    private record View(Entity entity) implements CorpseView {
        public Kind kind() { return entity instanceof CorpseEntity ? Kind.PLAYER : Kind.MOB; }
        public net.minecraft.resources.ResourceLocation sourceType() {
            return entity instanceof PlanCorpseEntity mob ? mob.sourceType() : EntityType.getKey(EntityType.PLAYER);
        }
        public Optional<UUID> sourceId() {
            if (entity instanceof CorpseEntity player) return Optional.ofNullable(player.getOwnerId());
            return ((PlanCorpseEntity) entity).sourceEntityId();
        }
        public Optional<UUID> ownerId() {
            return entity instanceof CorpseEntity player ? Optional.ofNullable(player.getOwnerId()) : Optional.empty();
        }
        public int ageTicks() { return entity instanceof CorpseEntity player ? player.age() : ((PlanCorpseEntity) entity).age(); }
        public State state() {
            if (entity.isRemoved()) return State.REMOVED;
            if (entity instanceof PlanCorpseEntity mob && mob.isReviving()) return State.REVIVING;
            if (entity.level.isClientSide) return State.UNKNOWN;
            if (grab().isPresent()) return State.GRABBED;
            return entity instanceof CorpseEntity player && player.isFrozen() ? State.FROZEN : State.ACTIVE;
        }
        public boolean supportsRevival() { return entity instanceof PlanCorpseEntity; }
        public boolean supportsLooting() { return entity instanceof CorpseEntity; }
        public List<Part> parts() {
            if (entity.level.isClientSide) return List.of();
            List<RigidBody> bodies;
            if (entity instanceof PlanCorpseEntity mob) bodies = mob.rigidBodies();
            else {
                var skeleton = ((CorpseEntity) entity).skeleton();
                if (skeleton == null) return List.of();
                bodies = skeleton.bodies();
            }
            List<Part> parts = new ArrayList<>(bodies.size());
            for (int i = 0; i < bodies.size(); i++) {
                var body = bodies.get(i);
                String id = entity instanceof PlanCorpseEntity mob ? mob.plan().boxes().get(i).path()
                        : PLAYER_PART_IDS.get(i);
                float scale = entity instanceof PlanCorpseEntity mob ? mob.limbScale(i) : 1;
                parts.add(new Part(i, id, new Vec3(body.pos.x, body.pos.y, body.pos.z),
                        new Rotation(body.rot.x, body.rot.y, body.rot.z, body.rot.w),
                        new Vec3(body.halfExtents.x, body.halfExtents.y, body.halfExtents.z), scale));
            }
            return List.copyOf(parts);
        }
        public int basePartIndex() {
            if (entity.level.isClientSide) return -1;
            if (entity instanceof PlanCorpseEntity mob) return mob.revivalBasePartIndex();
            var skeleton = ((CorpseEntity) entity).skeleton();
            if (skeleton == null) return -1;
            var bodies = skeleton.bodies();
            int index = -1;
            double volume = -1;
            for (int i = 0; i < bodies.size(); i++) {
                var half = bodies.get(i).halfExtents;
                double current = half.x * half.y * half.z;
                if (current > volume) { volume = current; index = i; }
            }
            return index;
        }
        public Optional<Grab> grab() {
            if (entity.level.isClientSide) return Optional.empty();
            return entity instanceof PlanCorpseEntity mob ? mob.grabInfo() : ((CorpseEntity) entity).grabInfo();
        }
        public Optional<Revival> revival() {
            return entity instanceof PlanCorpseEntity mob && !entity.level.isClientSide
                    ? mob.revivalInfo() : Optional.empty();
        }
    }
}
