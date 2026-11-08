package com.pyrostella.corpselab.client.model;

import com.pyrostella.corpselab.api.client.CorpseLayers.Adapter;
import com.pyrostella.corpselab.api.client.CorpseLayers.Context;
import com.pyrostella.corpselab.api.client.CorpseRenderContext;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pyrostella.corpselab.internal.registry.ExtensionRegistry;
import com.pyrostella.corpselab.api.Registration;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** 原版附加层绘制、扫描与共享模型状态恢复的客户端实现 */
public final class CorpseLayerRenderer {
    private static final ExtensionRegistry<Adapter> ADAPTERS = new ExtensionRegistry<>();
    private static final Field LAYERS = ObfuscationReflectionHelper.findField(LivingEntityRenderer.class, "f_115291_");
    private static final ClassValue<List<Field>> MODEL_FIELDS = fields(EntityModel.class);
    private static final ClassValue<List<Field>> PART_FIELDS = fields(ModelPart.class);

    private CorpseLayerRenderer() {
    }

    public static Registration register(ResourceLocation id, int priority, Adapter adapter) {
        return ADAPTERS.register(id.toString(), priority, adapter);
    }

    @SuppressWarnings("unchecked")
    public static void render(LivingEntityRenderer<?, ?> renderer, LivingEntity probe,
                              CorpseRenderContext corpse, PoseStack stack, MultiBufferSource buffers,
                              int light, float partialTick) {
        List<RenderLayer<?, ?>> layers;
        try {
            layers = List.copyOf((List<RenderLayer<?, ?>>) LAYERS.get(renderer));
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("无法读取模型附加层", failure);
        }
        probe.tickCount = corpse.entity().tickCount;
        for (RenderLayer<?, ?> layer : layers) {
            Context context = new Context(layer, renderer.getModel(), probe, corpse, stack, buffers, light, partialTick);
            Adapter selected = null;
            for (var entry : ADAPTERS.entries()) {
                if (entry.value().supports(layer)) {
                    selected = entry.value();
                    break;
                }
            }
            stack.pushPose();
            try {
                if (selected == null) {
                    context.renderModelParts();
                } else {
                    selected.render(context);
                }
            } finally {
                stack.popPose();
            }
        }
    }

    /** 默认附加层绘制与模型状态恢复属于客户端实现 */
    public static void renderModelParts(Context context, EntityModel<?>... extraModels) {
        Set<EntityModel<?>> models = Collections.newSetFromMap(new IdentityHashMap<>());
        models.add(context.model());
        for (Field field : MODEL_FIELDS.get(context.layer().getClass())) {
            Object value = read(field, context.layer());
            if (value instanceof EntityModel<?> entityModel) models.add(entityModel);
        }
        Collections.addAll(models, extraModels);
        List<ModelState> saved = models.stream().map(ModelState::new).toList();
        try {
            // 附加模型可能属于另一类实体，由原层自行准备，不能拿父实体强行调用其动画方法
            prepare(context.model(), context.probe(), context.partialTick(), context.corpse().entity().tickCount);
            ModelPartScanner.ScanResult base = scanRest(context.model());
            try (LayerPartContext parts = new LayerPartContext(context.corpse(), context.stack(), base, context.partialTick())) {
                models.forEach(parts::registerModel);
                MultiBufferSource guarded = type -> new LayerVertexConsumer(context.buffers().getBuffer(type));
                renderLayer(context.layer(), context.probe(), context.stack(), guarded, context.light(), context.partialTick(),
                        context.corpse().entity().tickCount + context.partialTick());
            }
        } finally {
            saved.forEach(ModelState::restore);
        }
    }

    /** 映射使用初始枢轴，避免上一只活体的动画残留影响外层模型的对齐 */
    public static ModelPartScanner.ScanResult scanRest(EntityModel<?> model) {
        ModelState saved = new ModelState(model);
        try {
            saved.parts.forEach(part -> part.part().resetPose());
            return ModelPartScanner.scanDetailed(model, true);
        } finally {
            saved.restore();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void prepare(EntityModel model, LivingEntity probe, float partialTick, int age) {
        model.young = probe.isBaby();
        model.riding = false;
        model.attackTime = 0;
        model.prepareMobModel(probe, 0, 0, partialTick);
        model.setupAnim(probe, 0, 0, age + partialTick, 0, 0);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderLayer(RenderLayer layer, LivingEntity probe, PoseStack stack,
                                    MultiBufferSource buffers, int light, float partialTick, float age) {
        layer.render(stack, buffers, light, probe, 0, 0, partialTick, age, 0, 0);
    }

    private static ClassValue<List<Field>> fields(Class<?> fieldType) {
        return new ClassValue<>() {
            @Override
            protected List<Field> computeValue(Class<?> type) {
                List<Field> result = new ArrayList<>();
                for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                    for (Field field : current.getDeclaredFields()) {
                        if (!Modifier.isStatic(field.getModifiers()) && fieldType.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            result.add(field);
                        }
                    }
                }
                return List.copyOf(result);
            }
        };
    }

    private static Object read(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("无法读取模型结构", failure);
        }
    }

    private static final class ModelState {
        private final EntityModel<?> model;
        private final boolean young;
        private final boolean riding;
        private final float attackTime;
        private final List<PartState> parts;

        private ModelState(EntityModel<?> model) {
            this.model = model;
            this.young = model.young;
            this.riding = model.riding;
            this.attackTime = model.attackTime;
            Set<ModelPart> all = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Field field : PART_FIELDS.get(model.getClass())) {
                Object value = read(field, model);
                if (value instanceof ModelPart part) collect(part, all);
            }
            this.parts = all.stream().map(PartState::new).toList();
        }

        private static void collect(ModelPart part, Set<ModelPart> parts) {
            if (parts.add(part)) part.children.values().forEach(child -> collect(child, parts));
        }

        private void restore() {
            this.model.young = this.young;
            this.model.riding = this.riding;
            this.model.attackTime = this.attackTime;
            this.parts.forEach(PartState::restore);
        }
    }

    private record PartState(ModelPart part, float x, float y, float z, float xRot, float yRot, float zRot,
                             float xScale, float yScale, float zScale, boolean visible, boolean skipDraw) {
        private PartState(ModelPart part) {
            this(part, part.x, part.y, part.z, part.xRot, part.yRot, part.zRot,
                    part.xScale, part.yScale, part.zScale, part.visible, part.skipDraw);
        }

        private void restore() {
            this.part.x = this.x;
            this.part.y = this.y;
            this.part.z = this.z;
            this.part.xRot = this.xRot;
            this.part.yRot = this.yRot;
            this.part.zRot = this.zRot;
            this.part.xScale = this.xScale;
            this.part.yScale = this.yScale;
            this.part.zScale = this.zScale;
            this.part.visible = this.visible;
            this.part.skipDraw = this.skipDraw;
        }
    }
}
