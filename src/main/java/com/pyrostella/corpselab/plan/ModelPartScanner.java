package com.pyrostella.corpselab.plan;

import net.minecraft.client.model.AgeableListModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 把一个原版模型拆成 {@link BodyPlan}
 *
 * <p><b>规则</b>：一个可见的 {@link ModelPart} 对应一个刚体，part 内部的多个 cuboid 合并成一个盒子
 * 关节有三路来源，优先级从高到低：part 的父子关系、{@link ModelTopology} 的手写拓补表
 * 按几何关系自动补全（见 {@link ModelTopology#resolve}）。</p>
 *
 * <p><b>为什么需要拓补表</b>：原版模型里四肢常常是根节点的兄弟——僵尸的腿和身体平级
 * 模型树里并没有"腿属于身体"这回事。只照抄 children 的话，这些生物的尸体会散成一地碎块。</p>
 *
 * <p><b>已知简化</b>：只累加平移，不累加 part 的旋转与缩放。原版模型在默认姿态下旋转基本为 0
 * 所以尺寸和相对位置是对的；带静态旋转的部件（比如某些模型的斜置角）会有偏差。</p>
 */
public final class ModelPartScanner {

    /** 模型像素 → 方块的换算 */
    private static final double PIXEL = 1.0D / 16.0D;

    /** 质量密度：每立方方块的质量 */
    private static final double DENSITY = 1.0D;

    /** 不公开父类方法，避免访问转换破坏全部原版子类的源码继承关系 */
    private static final Method HEAD_PARTS = ObfuscationReflectionHelper.findMethod(
            AgeableListModel.class, "m_5607_");
    private static final Method BODY_PARTS = ObfuscationReflectionHelper.findMethod(
            AgeableListModel.class, "m_5608_");

    private ModelPartScanner() {
    }

    /**
     * 一个盒子对应的模型部件
     *
     * @param part 模型部件
     * @param cx   cuboid 包围盒中心，相对部件原点，模型空间单位（像素 / 16，未做 Y 翻转）
     */
    public record PartRef(ModelPart part, float cx, float cy, float cz, PartGroup group) {
    }

    /** 幼年模型绘制时头部和身体使用不同变换 */
    public enum PartGroup {
        NORMAL,
        HEAD,
        BODY
    }

    private record RootRef(String path, ModelPart part, PartGroup group) {
    }

    /** 扫描结果：骨架 + 与盒子一一对应的模型部件 */
    public record ScanResult(BodyPlan plan, List<PartRef> parts) {
    }

    /** 只要骨架 */
    public static BodyPlan scan(EntityModel<?> model) {
        return scanDetailed(model).plan();
    }

    /** 骨架 + 部件对应关系（客户端渲染要用后者） */
    public static ScanResult scanDetailed(EntityModel<?> model) {
        return scanDetailed(model, false);
    }

    /** 尸体绑定需要完整部件表，不受共享模型当前动画的可见性影响 */
    public static ScanResult scanDetailed(EntityModel<?> model, boolean includeHidden) {
        List<BodyPlan.Box> boxes = new ArrayList<>();
        List<BodyPlan.Joint> treeJoints = new ArrayList<>();
        List<PartRef> parts = new ArrayList<>();

        // 同一个 part 可能既出现在 headParts/bodyParts 里、又是别的部件的子节点
        // 重复扫描会多出一个重叠的盒子，所以按实例去重
        Set<ModelPart> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        ModelTopology.Table topology = ModelTopology.tableFor(model);
        for (RootRef root : roots(model, topology)) {
            walk(root.part(), root.path(), root.group(), -1, 0.0D, 0.0D, 0.0D,
                    boxes, treeJoints, parts, visited, includeHidden);
        }

        List<BodyPlan.Joint> joints = ModelTopology.resolve(
                topology, List.copyOf(boxes), List.copyOf(treeJoints));

        return new ScanResult(new BodyPlan(List.copyOf(boxes), joints), List.copyOf(parts));
    }

    /**
     * 找到模型的根部件并给它们起名字
     *
     * <p>层级模型只有一个 root，名字留给子部件（子部件的键名就已经是 {@code head}/{@code body0} 这种）
     * 可长大模型没有 root，只能取 {@code headParts()/bodyParts()}，而下标本身没有含义
     * 所以名字从拓补表里查，查不到就退回 {@code head0}/{@code body0}。</p>
     */
    private static List<RootRef> roots(EntityModel<?> model, ModelTopology.Table topology) {
        Map<String, RootRef> roots = new LinkedHashMap<>();

        if (model instanceof HierarchicalModel<?> hierarchical) {
            roots.put("", new RootRef("", hierarchical.root(), PartGroup.NORMAL));
        } else if (model instanceof AgeableListModel<?> ageable) {
            int headIndex = 0;
            for (ModelPart head : parts(HEAD_PARTS, ageable)) {
                String name = ModelTopology.headName(topology, headIndex++);
                roots.putIfAbsent(name, new RootRef(name, head, PartGroup.HEAD));
            }
            int bodyIndex = 0;
            for (ModelPart body : parts(BODY_PARTS, ageable)) {
                String name = ModelTopology.bodyName(topology, bodyIndex++);
                roots.putIfAbsent(name, new RootRef(name, body, PartGroup.BODY));
            }
        }

        return List.copyOf(roots.values());
    }

    @SuppressWarnings("unchecked")
    private static Iterable<ModelPart> parts(Method method, AgeableListModel<?> model) {
        try {
            return (Iterable<ModelPart>) method.invoke(model);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("无法读取模型部件", e);
        } catch (InvocationTargetException e) {
            throw propagate("模型部件方法执行失败", e.getCause());
        }
    }

    private static RuntimeException propagate(String message, Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(message, cause);
    }

    private static void walk(ModelPart part, String path, PartGroup group, int parentBoxIndex,
                             double parentX, double parentY, double parentZ,
                             List<BodyPlan.Box> boxes, List<BodyPlan.Joint> treeJoints,
                             List<PartRef> parts, Set<ModelPart> visited, boolean includeHidden) {
        if (!visited.add(part)) {
            return;
        }

        // 部件自己的平移是相对父部件的，累加得到模型空间位置（单位仍是像素）
        double x = parentX + part.x;
        double y = parentY + part.y;
        double z = parentZ + part.z;

        int selfIndex = parentBoxIndex;

        if ((includeHidden || part.visible) && !part.cubes.isEmpty()) {
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;

            for (ModelPart.Cube cube : part.cubes) {
                minX = Math.min(minX, cube.minX);
                minY = Math.min(minY, cube.minY);
                minZ = Math.min(minZ, cube.minZ);
                maxX = Math.max(maxX, cube.maxX);
                maxY = Math.max(maxY, cube.maxY);
                maxZ = Math.max(maxZ, cube.maxZ);
            }

            double hx = (maxX - minX) * 0.5D * PIXEL;
            double hy = (maxY - minY) * 0.5D * PIXEL;
            double hz = (maxZ - minZ) * 0.5D * PIXEL;

            // 盒子中心 = 部件原点 + cuboid 包围盒的中心，再换算成方块
            double cx = (x + (minX + maxX) * 0.5D) * PIXEL;
            double cy = (y + (minY + maxY) * 0.5D) * PIXEL;
            double cz = (z + (minZ + maxZ) * 0.5D) * PIXEL;

            double volume = Math.max(1.0E-6D, 8.0D * hx * hy * hz);
            double mass = Math.max(0.05D, volume * DENSITY);

            selfIndex = boxes.size();
            // 枢轴 = 部件原点在模型空间的位置（姿势绕它旋转，关节也长在这里）
            boxes.add(new BodyPlan.Box(selfIndex, path.isEmpty() ? "root" : path,
                    cx, cy, cz, hx, hy, hz, mass,
                    x * PIXEL, y * PIXEL, z * PIXEL));

            // 部件本地坐标下的 cuboid 中心（方块单位），渲染时用来把 box 中心对准刚体
            parts.add(new PartRef(part,
                    (float) ((minX + maxX) * 0.5D * PIXEL),
                    (float) ((minY + maxY) * 0.5D * PIXEL),
                    (float) ((minZ + maxZ) * 0.5D * PIXEL), group));

            if (parentBoxIndex >= 0) {
                treeJoints.add(new BodyPlan.Joint(selfIndex, parentBoxIndex));
            }
        }

        for (Map.Entry<String, ModelPart> entry : part.children.entrySet()) {
            walk(entry.getValue(), path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(),
                    group, selfIndex, x, y, z, boxes, treeJoints, parts, visited, includeHidden);
        }
    }
}
