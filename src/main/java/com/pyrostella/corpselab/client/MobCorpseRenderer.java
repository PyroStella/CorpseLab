package com.pyrostella.corpselab.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pyrostella.corpselab.entity.MobCorpseEntity;
import com.pyrostella.corpselab.ragdoll.BodyPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * 用死亡生物自己的模型和纹理绘制它的身体
 *
 * <p>这里不按生物类型维护一张模型与纹理表，而是直接问游戏：借用 Minecraft
 * 原本就为该实体类型使用的渲染器，如果它的模型是类人模型就直接用那份模型
 * 这样模组添加的类人生物无需额外适配就能正常工作，资源包作用在尸体上的效果
 * 也和作用在活着的生物上完全一致
 *
 * <p>要从渲染器里取出纹理，需要该实体的一个实例，所以每种类型都会创建一个
 * 一次性实例并缓存起来。它永远不会被加入世界
 */
public class MobCorpseRenderer extends EntityRenderer<MobCorpseEntity> {

    /** 幼年生物大约按一半大小绘制，它们的尸体也应如此 */
    private static final float BABY_SCALE = 0.5F;

    private final Map<EntityType<?>, LivingEntity> probes = new HashMap<>();

    public MobCorpseRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(MobCorpseEntity entity) {
        ResourceLocation texture = resolveTexture(entity);
        return texture == null ? new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png") : texture;
    }

    /** 源生物的一次性实例，只用来向它的渲染器询问信息 */
    private LivingEntity probe(MobCorpseEntity entity) {
        EntityType<?> type = entity.sourceType().orElse(null);
        if (type == null || Minecraft.getInstance().level == null) {
            return null;
        }
        return probes.computeIfAbsent(type, t -> {
            Entity created = t.create(Minecraft.getInstance().level);
            return created instanceof LivingEntity living ? living : null;
        });
    }

    @SuppressWarnings("unchecked")
    private LivingEntityRenderer<LivingEntity, HumanoidModel<LivingEntity>> sourceRenderer(MobCorpseEntity entity) {
        LivingEntity probe = probe(entity);
        if (probe == null) {
            return null;
        }
        EntityRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(probe);
        if (!(renderer instanceof LivingEntityRenderer<?, ?> living)) {
            return null;
        }
        if (!(living.getModel() instanceof HumanoidModel<?>)) {
            return null;
        }
        return (LivingEntityRenderer<LivingEntity, HumanoidModel<LivingEntity>>) living;
    }

    private ResourceLocation resolveTexture(MobCorpseEntity entity) {
        LivingEntityRenderer<LivingEntity, HumanoidModel<LivingEntity>> renderer = sourceRenderer(entity);
        LivingEntity probe = probe(entity);
        return renderer == null || probe == null ? null : renderer.getTextureLocation(probe);
    }

    @Override
    public void render(MobCorpseEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int light) {
        if (!entity.hasPose()) {
            return;
        }

        LivingEntityRenderer<LivingEntity, HumanoidModel<LivingEntity>> renderer = sourceRenderer(entity);
        if (renderer == null) {
            // 不是类人模型，或者类型未知。什么都不画，好过用一只僵尸
            // 顶替真正死掉的那个生物
            return;
        }

        HumanoidModel<LivingEntity> model = renderer.getModel();
        entity.samplePose(partialTick);

        VertexConsumer consumer = buffer.getBuffer(
                RenderType.entityCutoutNoCull(getTextureLocation(entity)));

        double renderX = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double renderY = Mth.lerp(partialTick, entity.yOld, entity.getY());
        double renderZ = Mth.lerp(partialTick, entity.zOld, entity.getZ());

        float scale = entity.isBaby() ? BABY_SCALE : 1.0F;
        Quaternionf rotation = new Quaternionf();

        for (BodyPart part : BodyPart.values()) {
            Vec3 world = entity.partWorldPosition(part.ordinal());
            entity.partRotation(part.ordinal(), rotation);

            poseStack.pushPose();
            poseStack.translate(world.x - renderX, world.y - renderY, world.z - renderZ);
            poseStack.mulPose(rotation);
            poseStack.scale(-scale, -scale, scale);

            Vector3f centre = cuboidCentre(part);
            poseStack.translate(-centre.x / 16.0F, -centre.y / 16.0F, -centre.z / 16.0F);

            renderPart(modelPart(model, part), poseStack, consumer, light);

            poseStack.popPose();
        }

        super.render(entity, entityYaw, partialTick, poseStack, buffer, light);
    }

    private static void renderPart(ModelPart part, PoseStack poseStack, VertexConsumer consumer, int light) {
        if (part == null) {
            return;
        }
        part.x = 0.0F;
        part.y = 0.0F;
        part.z = 0.0F;
        part.xRot = 0.0F;
        part.yRot = 0.0F;
        part.zRot = 0.0F;
        part.visible = true;
        part.render(poseStack, consumer, light, OverlayTexture.NO_OVERLAY);
    }

    private static ModelPart modelPart(HumanoidModel<?> model, BodyPart part) {
        return switch (part) {
            case HEAD -> model.head;
            case TORSO -> model.body;
            case ARM_LEFT -> model.leftArm;
            case ARM_RIGHT -> model.rightArm;
            case LEG_LEFT -> model.leftLeg;
            case LEG_RIGHT -> model.rightLeg;
        };
    }

    /**
     * Minecraft 类人模型各长方体中心的坐标，单位为模型像素
     *
     * <p>这里用的是类人模型通用的数值。少数生物会把肢体收窄，比如
     * 骷髅的手臂宽两个像素而不是四个，这会让那些中心偏移半个像素
     * 这点偏移只有三十二分之一个方块，不值得为此去读每个模型
     * 私有的立方体数据来修正
     */
    private static Vector3f cuboidCentre(BodyPart part) {
        return switch (part) {
            case HEAD -> new Vector3f(0.0F, -4.0F, 0.0F);
            case TORSO -> new Vector3f(0.0F, 6.0F, 0.0F);
            case ARM_LEFT -> new Vector3f(1.0F, 4.0F, 0.0F);
            case ARM_RIGHT -> new Vector3f(-1.0F, 4.0F, 0.0F);
            case LEG_LEFT, LEG_RIGHT -> new Vector3f(0.0F, 6.0F, 0.0F);
        };
    }
}
