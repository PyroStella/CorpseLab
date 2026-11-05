package com.pyrostella.corpselab.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.model.AgeableListModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;

/**
 * 把一个原版模型拆成 {@link BodyPlan}。
 *
 * <p><b>规则</b>：一个可见的 {@link ModelPart} 对应一个刚体，part 内部的多个 cuboid 合并成一个盒子；
 * 关节直接照抄 part 的父子关系。这样不需要为任何生物手写拓扑——僵尸自动 6 盒 5 关节，
 * 蜘蛛自动身体加八条腿，末影龙自动一串。</p>
 *
 * <p><b>已知简化</b>：只累加平移，不累加 part 的旋转与缩放。原版模型在默认姿态下旋转基本为 0，
 * 所以尺寸和相对位置是对的；带静态旋转的部件（比如某些模型的斜置角）会有偏差。</p>
 */
public final class ModelPartScanner {

    /** 模型像素 → 方块的换算 */
    private static final double PIXEL = 1.0D / 16.0D;

    /** 质量密度：每立方方块的质量 */
    private static final double DENSITY = 1.0D;

    private ModelPartScanner() {
    }

    /**
     * 一个盒子对应的模型部件。
     *
     * @param part 模型部件
     * @param cx   cuboid 包围盒中心，相对部件原点，模型空间单位（像素 / 16，未做 Y 翻转）
     */
    public record PartRef(ModelPart part, float cx, float cy, float cz) {
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
        List<BodyPlan.Box> boxes = new ArrayList<>();
        List<BodyPlan.Joint> joints = new ArrayList<>();
        List<PartRef> parts = new ArrayList<>();

        for (ModelPart root : roots(model)) {
            walk(root, "", -1, 0.0D, 0.0D, 0.0D, boxes, joints, parts);
        }

        return new ScanResult(new BodyPlan(List.copyOf(boxes), List.copyOf(joints)), List.copyOf(parts));
    }

    /** 找到模型的根部件。原版两条路径：层级模型取 root，可长大模型取头/身两张表 */
    private static List<ModelPart> roots(EntityModel<?> model) {
        if (model instanceof HierarchicalModel<?> hierarchical) {
            return List.of(hierarchical.root());
        }
        if (model instanceof AgeableListModel<?> ageable) {
            List<ModelPart> list = new ArrayList<>();
            for (ModelPart part : ageable.headParts()) {
                list.add(part);
            }
            for (ModelPart part : ageable.bodyParts()) {
                list.add(part);
            }
            return list;
        }
        return List.of();
    }

    private static void walk(ModelPart part, String path, int parentBoxIndex,
                             double parentX, double parentY, double parentZ,
                             List<BodyPlan.Box> boxes, List<BodyPlan.Joint> joints,
                             List<PartRef> parts) {
        // 部件自己的平移是相对父部件的，累加得到模型空间位置（单位仍是像素）
        double x = parentX + part.x;
        double y = parentY + part.y;
        double z = parentZ + part.z;

        int selfIndex = parentBoxIndex;

        if (part.visible && !part.cubes.isEmpty()) {
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
                    (float) ((minZ + maxZ) * 0.5D * PIXEL)));

            if (parentBoxIndex >= 0) {
                joints.add(new BodyPlan.Joint(selfIndex, parentBoxIndex));
            }
        }

        for (Map.Entry<String, ModelPart> entry : part.children.entrySet()) {
            walk(entry.getValue(), path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(),
                    selfIndex, x, y, z, boxes, joints, parts);
        }
    }
}
