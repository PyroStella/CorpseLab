package com.pyrostella.corpselab.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pyrostella.corpselab.plan.BodyPlan;
import com.pyrostella.corpselab.plan.ModelPartScanner.PartGroup;
import com.pyrostella.corpselab.plan.ModelPartScanner.PartRef;
import com.pyrostella.corpselab.plan.ModelPartScanner.ScanResult;

import net.minecraft.client.model.AgeableListModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** 把当前实体渲染时的缩放固化进单实例 BodyPlan */
public final class ModelRenderPlan {

    /** LivingEntityRenderer 在模型缩放后使用的脚底抬升 */
    private static final float LIFT = 1.501F;

    private ModelRenderPlan() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static BodyPlan capture(ScanResult scan, EntityModel<?> model,
                                   LivingEntityRenderer<?, ?> renderer,
                                   LivingEntity entity, float partialTick) {
        PoseStack rendererTransform = new PoseStack();
        ((LivingEntityRenderer) renderer).scale(entity, rendererTransform, partialTick);
        Matrix4f matrix = rendererTransform.last().pose();

        Vector3f xAxis = matrix.transformDirection(new Vector3f(1.0F, 0.0F, 0.0F));
        Vector3f yAxis = matrix.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F));
        Vector3f zAxis = matrix.transformDirection(new Vector3f(0.0F, 0.0F, 1.0F));
        double rendererX = xAxis.length();
        double rendererY = yAxis.length();
        double rendererZ = zAxis.length();

        List<BodyPlan.Box> boxes = new ArrayList<>(scan.plan().boxCount());
        for (BodyPlan.Box box : scan.plan().boxes()) {
            PartRef part = scan.parts().get(box.index());
            BabyTransform baby = babyTransform(model, entity, part.group());

            Vector3f centre = transform(matrix, baby,
                    (float) box.cx(), (float) box.cy(), (float) box.cz());
            Vector3f pivot = transform(matrix, baby,
                    (float) box.px(), (float) box.py(), (float) box.pz());

            double sx = baby.scale() * rendererX;
            double sy = baby.scale() * rendererY;
            double sz = baby.scale() * rendererZ;
            double massScale = Math.max(1.0E-6D, sx * sy * sz);

            boxes.add(new BodyPlan.Box(box.index(), box.path(),
                    centre.x, centre.y, centre.z,
                    box.hx() * sx, box.hy() * sy, box.hz() * sz,
                    box.mass() * massScale,
                    pivot.x, pivot.y, pivot.z));
        }
        return new BodyPlan(List.copyOf(boxes), scan.plan().joints());
    }

    private static Vector3f transform(Matrix4f renderer, BabyTransform baby,
                                      float x, float y, float z) {
        Vector3f point = new Vector3f(
                baby.scale() * x,
                baby.scale() * (y + baby.offsetY()),
                baby.scale() * (z + baby.offsetZ()));

        // 原版顺序是 renderer.scale → translate(-1.501) → 幼年模型变换
        point.y -= LIFT;
        renderer.transformPosition(point);
        point.y += LIFT;
        return point;
    }

    private static BabyTransform babyTransform(EntityModel<?> model, LivingEntity entity,
                                                PartGroup group) {
        if (!entity.isBaby() || !(model instanceof AgeableListModel<?> ageable)) {
            return BabyTransform.IDENTITY;
        }

        if (group == PartGroup.HEAD) {
            float scale = ageable.scaleHead ? 1.5F / ageable.babyHeadScale : 1.0F;
            return new BabyTransform(scale,
                    ageable.babyYHeadOffset / 16.0F,
                    ageable.babyZHeadOffset / 16.0F);
        }
        if (group == PartGroup.BODY) {
            return new BabyTransform(1.0F / ageable.babyBodyScale,
                    ageable.bodyYOffset / 16.0F, 0.0F);
        }
        return BabyTransform.IDENTITY;
    }

    private record BabyTransform(float scale, float offsetY, float offsetZ) {
        private static final BabyTransform IDENTITY = new BabyTransform(1.0F, 0.0F, 0.0F);
    }
}
