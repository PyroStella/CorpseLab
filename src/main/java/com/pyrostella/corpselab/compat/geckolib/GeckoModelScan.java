package com.pyrostella.corpselab.compat.geckolib;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pyrostella.corpselab.api.model.BodyPlan;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.*;
import software.bernie.geckolib.core.state.BoneSnapshot;
import software.bernie.geckolib.util.RenderUtils;

import java.util.ArrayList;
import java.util.List;

/** 使用 JSON 烘焙后的真实骨骼树，不经过原版模型的拓扑补全 */
public final class GeckoModelScan {
    public record Part(GeoBone bone, Matrix4f transform) {}
    public record Result(BodyPlan plan, List<Part> parts) {}

    private GeckoModelScan() {}

    public static Result scan(BakedGeoModel model, Matrix4f rendererTransform) {
        List<BodyPlan.Box> boxes = new ArrayList<>();
        List<BodyPlan.Joint> joints = new ArrayList<>();
        List<Part> parts = new ArrayList<>();
        for (GeoBone root : model.topLevelBones()) {
            walk(root, root.getName(), -1, rendererTransform, boxes, joints, parts);
        }
        return physicalBones(boxes, joints, parts);
    }

    /** 空控制骨骼只传递变换，不生成具有极小转动惯量的碰撞刚体 */
    private static Result physicalBones(List<BodyPlan.Box> boxes, List<BodyPlan.Joint> joints, List<Part> parts) {
        int[] parent = new int[boxes.size()];
        int[] remap = new int[boxes.size()];
        java.util.Arrays.fill(parent, -1);
        java.util.Arrays.fill(remap, -1);
        for (var joint : joints) parent[joint.child()] = joint.parent();
        List<BodyPlan.Box> physical = new ArrayList<>();
        List<Part> geometry = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            var bone = parts.get(i).bone();
            boolean hasGeometry = !Boolean.TRUE.equals(bone.shouldNeverRender()) && bone.getCubes().stream()
                    .anyMatch(cube -> cube.quads() != null && java.util.Arrays.stream(cube.quads())
                            .anyMatch(quad -> quad != null && quad.vertices().length > 0));
            if (!hasGeometry) continue;
            var box = boxes.get(i);
            int index = physical.size();
            remap[i] = index;
            physical.add(new BodyPlan.Box(index, box.path(), box.cx(), box.cy(), box.cz(),
                    box.hx(), box.hy(), box.hz(), box.mass(), box.px(), box.py(), box.pz()));
            geometry.add(parts.get(i));
        }
        List<BodyPlan.Joint> links = new ArrayList<>();
        var representatives = new java.util.HashMap<Integer, Integer>();
        for (int i = 0; i < boxes.size(); i++) {
            if (remap[i] < 0) continue;
            int root = i;
            while (parent[root] >= 0) root = parent[root];
            representatives.putIfAbsent(root, remap[i]);
        }
        for (int i = 0; i < boxes.size(); i++) {
            if (remap[i] < 0) continue;
            int ancestor = parent[i];
            while (ancestor >= 0 && remap[ancestor] < 0) ancestor = parent[ancestor];
            int target;
            if (ancestor >= 0) target = remap[ancestor];
            else {
                int root = i;
                while (parent[root] >= 0) root = parent[root];
                // 同一个空根下的几何分支归属同一装配体，独立的 JSON 根仍彼此独立
                target = representatives.get(root);
            }
            if (target != remap[i]) links.add(new BodyPlan.Joint(remap[i], target));
        }
        return new Result(new BodyPlan(List.copyOf(physical), List.copyOf(links)), List.copyOf(geometry));
    }
    private static void walk(GeoBone bone, String path, int parent, Matrix4f parentMatrix,
                             List<BodyPlan.Box> boxes, List<BodyPlan.Joint> joints, List<Part> parts) {
        // 只读取初始快照，避免共享模型上最后一个活体的动画污染尸体和其它实例
        BoneSnapshot rest = bone.getInitialSnapshot();
        if (rest == null) rest = new BoneSnapshot(bone);
        Vector3f pivot = new Vector3f(bone.getPivotX()/16f, bone.getPivotY()/16f, bone.getPivotZ()/16f);
        Matrix4f matrix = new Matrix4f(parentMatrix)
                .translate(-rest.getOffsetX()/16f, rest.getOffsetY()/16f, rest.getOffsetZ()/16f)
                .translate(pivot).rotateZ(rest.getRotZ()).rotateY(rest.getRotY()).rotateX(rest.getRotX())
                .scale(rest.getScaleX(), rest.getScaleY(), rest.getScaleZ()).translate(-pivot.x, -pivot.y, -pivot.z);
        Vector3f worldPivot = matrix.transformPosition(new Vector3f(pivot));
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (GeoCube cube : bone.getCubes()) {
            if (cube.quads() == null) continue;
            PoseStack cubeStack = new PoseStack();
            cubeStack.mulPoseMatrix(matrix);
            RenderUtils.translateToPivotPoint(cubeStack, cube);
            RenderUtils.rotateMatrixAroundCube(cubeStack, cube);
            RenderUtils.translateAwayFromPivotPoint(cubeStack, cube);
            for (GeoQuad quad : cube.quads()) {
                if (quad == null) continue;
                for (GeoVertex vertex : quad.vertices()) {
                    Vector3f point = cubeStack.last().pose().transformPosition(new Vector3f(vertex.position()));
                    min.min(point);
                    max.max(point);
                }
            }
        }
        // 临时记录空骨骼的枢轴，物理过滤阶段会移除它，子骨骼仍继承完整变换
        if (!Float.isFinite(min.x)) {
            min.set(worldPivot).sub(0.005f, 0.005f, 0.005f);
            max.set(worldPivot).add(0.005f, 0.005f, 0.005f);
        }
        Vector3f centre = new Vector3f(min).add(max).mul(0.5f);
        Vector3f half = new Vector3f(max).sub(min).mul(0.5f).max(new Vector3f(0.001f));
        int index = boxes.size();
        // 统一到原版 BodyPlan 空间，物理层随后会执行相反的轴变换
        boxes.add(new BodyPlan.Box(index, path, -centre.x, 1.501-centre.y, centre.z,
                half.x, half.y, half.z, Math.max(0.05, 8*half.x*half.y*half.z),
                -worldPivot.x, 1.501-worldPivot.y, worldPivot.z));
        parts.add(new Part(bone, matrix));
        if (parent >= 0) joints.add(new BodyPlan.Joint(index, parent));
        for (GeoBone child : bone.getChildBones()) {
            walk(child, path + "." + child.getName(), index, matrix, boxes, joints, parts);
        }
    }
}