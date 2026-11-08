package com.pyrostella.corpselab.api;

import com.pyrostella.corpselab.api.model.BodyPlan;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** 通用初始化阶段注册服务端可用的物理骨架，不依赖客户端发现模型 */
public final class CorpseSkeletons {
    private static final Map<EntityType<?>, Provider> PROVIDERS = new ConcurrentHashMap<>();
    private CorpseSkeletons() { }
    public static Registration register(EntityType<?> type, Function<LivingEntity,BodyPlan> provider) {
        Objects.requireNonNull(type); Objects.requireNonNull(provider);
        Provider registration = new Provider(provider);
        if (PROVIDERS.putIfAbsent(type, registration) != null) throw new IllegalArgumentException("骨架提供器重复注册");
        return () -> PROVIDERS.remove(type, registration);
    }
    public static BodyPlan get(LivingEntity entity) {
        var provider = PROVIDERS.get(entity.getType());
        if (provider == null) return null;
        BodyPlan plan = provider.factory.apply(entity);
        if (plan == null || plan.boxCount() == 0 || !plan.isValid()) throw new IllegalArgumentException("骨架提供器返回无效数据");
        return plan;
    }
    /** 每次注册拥有独立身份，即使复用相同函数也不会被旧句柄误删 */
    private static final class Provider {
        private final Function<LivingEntity, BodyPlan> factory;
        private Provider(Function<LivingEntity, BodyPlan> factory) { this.factory = factory; }
    }
    public static Builder builder() { return new Builder(); }
    /** 输入为脚底原点、Y 向上的身体局部坐标；尺寸使用半边长，单位为格 */
    public static final class Builder {
        private final List<BodyPlan.Box> boxes = new ArrayList<>();
        private final List<BodyPlan.Joint> joints = new ArrayList<>();
        private final Map<String,Integer> indices = new HashMap<>();
        public Builder box(String id, Vec3 centre, Vec3 halfSize, double mass) {
            return box(id,centre,halfSize,mass,centre);
        }
        public Builder box(String id, Vec3 centre, Vec3 halfSize, double mass, Vec3 pivot) {
            if (id == null || id.isBlank() || indices.containsKey(id)) throw new IllegalArgumentException("部件 ID 为空或重复");
            int index = boxes.size(); indices.put(id,index);
            boxes.add(new BodyPlan.Box(index,id,-centre.x,1.501-centre.y,centre.z,
                    halfSize.x,halfSize.y,halfSize.z,mass,-pivot.x,1.501-pivot.y,pivot.z));
            return this;
        }
        public Builder joint(String child, String parent) {
            if (!indices.containsKey(child) || !indices.containsKey(parent)) throw new IllegalArgumentException("关节引用未知部件");
            joints.add(new BodyPlan.Joint(indices.get(child),indices.get(parent))); return this;
        }
        public BodyPlan build() {
            BodyPlan plan = new BodyPlan(List.copyOf(boxes),List.copyOf(joints));
            if (plan.boxCount() == 0 || !plan.isValid()) throw new IllegalArgumentException("骨架数据无效");
            return plan;
        }
    }
}
