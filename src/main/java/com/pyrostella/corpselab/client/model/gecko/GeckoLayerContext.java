package com.pyrostella.corpselab.client.model.gecko;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pyrostella.corpselab.entity.PlanCorpseEntity;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.util.RenderUtils;
import java.util.IdentityHashMap;
import java.util.Map;

/** 附加层再次绘制共享 GeoCube 时，输出到尸体当前物理位置 */
public final class GeckoLayerContext implements AutoCloseable {
    private static final ThreadLocal<GeckoLayerContext> ACTIVE = new ThreadLocal<>();
    private final GeckoLayerContext previous;
    private final Map<GeoCube, PoseStack.Pose> transforms = new IdentityHashMap<>();

    public GeckoLayerContext(PlanCorpseEntity corpse, PoseStack root, GeckoModelScan.Result scan, float tick) {
        previous = ACTIVE.get();
        var targets = new java.util.HashMap<String, com.pyrostella.corpselab.plan.BodyPlan.Box>();
        corpse.plan().boxes().forEach(box -> targets.put(box.path(), box));
        for (var base : scan.plan().boxes()) {
            var actual = targets.get(base.path());
            if (actual == null) continue;
            float revivalScale = corpse.limbScale(actual.index());
            if (revivalScale <= 0) continue;
            var part = scan.parts().get(base.index());
            var position = corpse.poses().position(actual.index());
            PoseStack stack = new PoseStack();
            stack.last().pose().set(root.last().pose());
            stack.last().normal().set(root.last().normal());
            stack.translate(position.x-Mth.lerp(tick, corpse.xOld, corpse.getX()),
                    position.y-Mth.lerp(tick, corpse.yOld, corpse.getY()),
                    position.z-Mth.lerp(tick, corpse.zOld, corpse.getZ()));
            stack.mulPose(corpse.poses().rotation(actual.index(), new Quaternionf()));
            stack.scale(revivalScale,revivalScale,revivalScale);
            stack.scale((float)(actual.hx()/base.hx()), (float)(actual.hy()/base.hy()), (float)(actual.hz()/base.hz()));
            stack.translate(base.cx(), base.cy()-1.501, -base.cz());
            stack.mulPoseMatrix(part.transform());
            stack.last().normal().mul(new Matrix3f(part.transform()).invert().transpose());
            for (GeoCube cube : part.bone().getCubes()) {
                PoseStack cubeStack = new PoseStack();
                cubeStack.last().pose().set(stack.last().pose());
                cubeStack.last().normal().set(stack.last().normal());
                RenderUtils.translateToPivotPoint(cubeStack, cube);
                RenderUtils.rotateMatrixAroundCube(cubeStack, cube);
                RenderUtils.translateAwayFromPivotPoint(cubeStack, cube);
                transforms.put(cube, cubeStack.last());
            }
        }
        ACTIVE.set(this);
    }

    public static boolean redirect(GeoCube cube, VertexConsumer buffer, int light, int overlay,
                                   float red, float green, float blue, float alpha) {
        var context = ACTIVE.get();
        if (context == null) return false;
        var pose = context.transforms.get(cube);
        if (pose == null || cube.quads() == null) return true;
        for (var quad : cube.quads()) {
            if (quad == null) continue;
            Vector3f normal = pose.normal().transform(new Vector3f(quad.normal()));
            RenderUtils.fixInvertedFlatCube(cube, normal);
            for (var vertex : quad.vertices()) {
                Vector4f point = pose.pose().transform(new Vector4f(vertex.position(), 1));
                buffer.vertex(point.x, point.y, point.z, red, green, blue, alpha, vertex.texU(), vertex.texV(),
                        overlay, light, normal.x, normal.y, normal.z);
            }
        }
        return true;
    }

    public void close() {
        if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
    }
}