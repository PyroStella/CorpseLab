package com.pyrostella.corpselab.api.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pyrostella.corpselab.api.RevivalPose;
import com.pyrostella.corpselab.client.compat.PoseCompat;
import com.pyrostella.corpselab.client.model.ModelPartScanner;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.*;

/** 仅客户端调用；在共享模型上临时采样，完成或异常时均恢复原状态 */
public final class VanillaRevivalPoses {
    private VanillaRevivalPoses() { }
    public static RevivalPose firstFrame(HierarchicalModel<?> model, AnimationDefinition animation) {
        return capture(model, () -> KeyframeAnimations.animate(model,animation,0,1,new com.mojang.math.Vector3f()));
    }
    /** 外部 Java 动画也可通过此入口先应用模型状态，再取得统一姿态 */
    public static RevivalPose capture(HierarchicalModel<?> model, Runnable applyPose) {
        Map<ModelPart, Saved> saved = new IdentityHashMap<>();
        model.root().getAllParts().forEach(part -> saved.put(part,new Saved(part.storePose(),part.xScale,part.yScale,part.zScale,part.visible,part.skipDraw)));
        try {
            model.root().getAllParts().forEach(ModelPart::resetPose);
            applyPose.run();
            Map<ModelPart,Matrix4f> matrices = new IdentityHashMap<>();
            collect(model.root(),new PoseStack(),matrices);
            var scan = ModelPartScanner.scanDetailed(model,true);
            RevivalPose pose = RevivalPose.empty();
            for (var box : scan.plan().boxes()) {
                var ref = scan.parts().get(box.index());
                Matrix4f matrix = matrices.get(ref.part());
                Vector3f centre = matrix.transformPosition(new Vector3f(ref.cx(),ref.cy(),ref.cz()));
                Quaternionf q = matrix.getUnnormalizedRotation(new Quaternionf()).normalize();
                pose = pose.bone(box.path(),new Vec3(-centre.x,1.501-centre.y,centre.z),
                        new Quaterniond(-q.x,-q.y,q.z,q.w),uniformScale(matrix));
            }
            return pose;
        } finally {
            saved.forEach((part,state) -> {
                part.loadPose(state.pose); part.xScale=state.x; part.yScale=state.y; part.zScale=state.z; part.visible=state.visible; part.skipDraw=state.skipDraw;
            });
        }
    }
    private static void collect(ModelPart part, PoseStack stack, Map<ModelPart,Matrix4f> matrices) {
        stack.pushPose();
        part.translateAndRotate(stack);
        matrices.put(part, PoseCompat.toJoml(stack.last().pose()));
        for (var child : part.children.values()) collect(child,stack,matrices);
        stack.popPose();
    }
    /** 当前网络部件缩放是标量，不能静默丢弃非均匀缩放或镜像 */
    public static float uniformScale(Matrix4f matrix) {
        Vector3f scale = matrix.getScale(new Vector3f());
        if (matrix.determinant() <= 0 || Math.abs(scale.x-scale.y)>0.0001 || Math.abs(scale.x-scale.z)>0.0001
                || !Float.isFinite(scale.x) || scale.x<=0)
            throw new IllegalArgumentException("目标动画暂只支持正的均匀缩放");
        return scale.x;
    }
    private record Saved(PartPose pose,float x,float y,float z,boolean visible,boolean skipDraw) { }
}
