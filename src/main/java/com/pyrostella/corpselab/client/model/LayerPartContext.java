package com.pyrostella.corpselab.client.model;


import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pyrostella.corpselab.api.client.CorpseRenderContext;
import com.pyrostella.corpselab.client.compat.PoseCompat;
import com.pyrostella.corpselab.api.model.BodyPlan;
import com.pyrostella.corpselab.client.model.ModelPartScanner;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** 仅在尸体附加层绘制期间，把 ModelPart 的方块输出转到对应物理肢体 */
public final class LayerPartContext implements AutoCloseable {
    private static final ThreadLocal<LayerPartContext> ACTIVE = new ThreadLocal<>();
    private final LayerPartContext previous;
    private final CorpseRenderContext corpse;
    private final PoseStack.Pose root;
    private final ModelPartScanner.ScanResult base;
    private final Map<String, BodyPlan.Box> actual = new HashMap<>();
    private final Map<String, BodyPlan.Box> baseBoxes = new HashMap<>();
    private final Map<ModelPart, PoseStack.Pose> transforms = new IdentityHashMap<>();
    private final float partialTick;
    private boolean compiling;

    public LayerPartContext(CorpseRenderContext corpse, PoseStack stack,
                            ModelPartScanner.ScanResult base, float partialTick) {
        this.previous = ACTIVE.get();
        this.corpse = corpse;
        this.root = stack.last();
        this.base = base;
        this.partialTick = partialTick;
        corpse.plan().boxes().forEach(box -> this.actual.put(box.path(), box));
        base.plan().boxes().forEach(box -> this.baseBoxes.put(box.path(), box));
        ACTIVE.set(this);
    }

    public void registerModel(EntityModel<?> model) {
        ModelPartScanner.ScanResult scan = CorpseLayerRenderer.scanRest(model);
        for (BodyPlan.Box source : scan.plan().boxes()) {
            ModelPart part = scan.parts().get(source.index()).part();
            if (this.transforms.containsKey(part)) {
                continue;
            }
            BodyPlan.Box target = this.target(source);
            if (target == null) {
                continue;
            }
            BodyPlan.Box original = this.baseBoxes.get(target.path());
            if (original == null) {
                continue;
            }
            float revivalScale = this.corpse.partScale(target.index());
            if (revivalScale <= 0) continue;
            var ref = this.base.parts().get(original.index());
            var position = this.corpse.partPosition(target.index());
            PoseStack transform = new PoseStack();
            // 1.19.2 的矩阵是 com.mojang.math 自己的类型，拷贝用 load 而不是 JOML 的 set
            transform.last().pose().load(this.root.pose());
            transform.last().normal().load(this.root.normal());
            transform.translate(position.x - Mth.lerp(this.partialTick, this.corpse.entity().xOld, this.corpse.entity().getX()),
                    position.y - Mth.lerp(this.partialTick, this.corpse.entity().yOld, this.corpse.entity().getY()),
                    position.z - Mth.lerp(this.partialTick, this.corpse.entity().zOld, this.corpse.entity().getZ()));
            PoseCompat.mulPose(transform, this.corpse.partRotation(target.index(), new Quaternionf()));
            transform.scale(revivalScale,revivalScale,revivalScale);
            transform.scale(-1, -1, 1);
            transform.scale(scale(target.hx(), original.hx()), scale(target.hy(), original.hy()),
                    scale(target.hz(), original.hz()));
            transform.translate(source.px() - original.px() - ref.cx(),
                    source.py() - original.py() - ref.cy(), source.pz() - original.pz() - ref.cz());
            this.transforms.put(part, transform.last());
        }
    }

    private BodyPlan.Box target(BodyPlan.Box source) {
        String path = source.path();
        while (true) {
            BodyPlan.Box exact = this.actual.get(path);
            if (exact != null) {
                return exact;
            }
            int dot = path.lastIndexOf('.');
            if (dot < 0) {
                break;
            }
            path = path.substring(0, dot);
        }
        // 帽子等独立根部件可能与头部共用枢轴，但没有单独的物理块
        for (BodyPlan.Box box : this.base.plan().boxes()) {
            if (this.actual.containsKey(box.path()) && Math.abs(box.px() - source.px()) < 1.0E-6D
                    && Math.abs(box.py() - source.py()) < 1.0E-6D
                    && Math.abs(box.pz() - source.pz()) < 1.0E-6D) {
                return this.actual.get(box.path());
            }
        }
        return null;
    }

    private static float scale(double actual, double model) {
        return model > 1.0E-6D ? (float) (actual / model) : 1.0F;
    }

    public static boolean redirect(ModelPart part, VertexConsumer consumer, int light, int overlay,
                                    float red, float green, float blue, float alpha) {
        LayerPartContext context = ACTIVE.get();
        if (context == null) {
            return false;
        }
        PoseStack.Pose transform = context.transforms.get(part);
        if (transform != null) {
            context.compiling = true;
            try {
                for (ModelPart.Cube cube : part.cubes) {
                    cube.compile(transform, consumer, light, overlay, red, green, blue, alpha);
                }
            } finally {
                context.compiling = false;
            }
        }
        return true;
    }

    static boolean acceptsVertices() {
        LayerPartContext context = ACTIVE.get();
        return context != null && context.compiling;
    }

    @Override
    public void close() {
        if (this.previous == null) {
            ACTIVE.remove();
        } else {
            ACTIVE.set(this.previous);
        }
    }
}
