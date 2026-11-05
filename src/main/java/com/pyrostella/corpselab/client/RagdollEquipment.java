package com.pyrostella.corpselab.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pyrostella.corpselab.entity.CorpseEntity;
import com.pyrostella.corpselab.ragdoll.BodyPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.ItemStack;

/**
 * 画出布娃娃的盔甲，让穿一身钻石甲的玩家倒下后
 * 不至于看起来像换了个人
 *
 * <p>复用原版自己的盔甲模型和贴图，并沿用身体模型那套
 * 按长方体逐个摆放的方式
 */
public final class RagdollEquipment {

    private RagdollEquipment() {
    }

    /** 某个身体部位对应哪个盔甲槽位。腿会被盖两次 */
    private static EquipmentSlot primarySlot(BodyPart part) {
        return switch (part) {
            case HEAD -> EquipmentSlot.HEAD;
            case TORSO, ARM_LEFT, ARM_RIGHT -> EquipmentSlot.CHEST;
            case LEG_LEFT, LEG_RIGHT -> EquipmentSlot.LEGS;
        };
    }

    /**
     * 用栈上已有的变换，画出盖住这个部位的盔甲
     *
     * @param inner 原版给护腿用的那套更贴身的模型
     * @param outer 原版给其他部位用的模型
     */
    public static void render(CorpseEntity entity, BodyPart part,
                              PoseStack poseStack, MultiBufferSource buffer, int light,
                              HumanoidModel<?> inner, HumanoidModel<?> outer) {
        EquipmentSlot slot = primarySlot(part);
        // 护腿用贴身的内层模型，其余部位用外层模型
        boolean useInner = slot == EquipmentSlot.LEGS;

        renderPiece(entity.equipment(slot), part, poseStack, buffer, light,
                useInner ? inner : outer, useInner);

        // 腿上还要再穿一只靴子，画在护腿之上
        if (part == BodyPart.LEG_LEFT || part == BodyPart.LEG_RIGHT) {
            renderPiece(entity.equipment(EquipmentSlot.FEET), part, poseStack, buffer,
                    light, outer, false);
        }
    }

    private static void renderPiece(ItemStack stack, BodyPart part, PoseStack poseStack,
                                    MultiBufferSource buffer, int light,
                                    HumanoidModel<?> model, boolean inner) {
        if (!(stack.getItem() instanceof ArmorItem armor)) {
            return;
        }

        ModelPart piece = modelPart(model, part);
        if (piece == null) {
            return;
        }

        // 皮革按染料染色，上面再叠一层未染色的遮罩
        if (stack.getItem() instanceof DyeableLeatherItem dyeable) {
            int colour = dyeable.getColor(stack);
            draw(piece, poseStack, buffer, light, texture(armor, inner, null),
                    (colour >> 16 & 255) / 255.0F,
                    (colour >> 8 & 255) / 255.0F,
                    (colour & 255) / 255.0F);
            draw(piece, poseStack, buffer, light, texture(armor, inner, "overlay"), 1.0F, 1.0F, 1.0F);
            return;
        }

        draw(piece, poseStack, buffer, light, texture(armor, inner, null), 1.0F, 1.0F, 1.0F);
    }

    private static void draw(ModelPart piece, PoseStack poseStack, MultiBufferSource buffer,
                             int light, ResourceLocation texture, float red, float green, float blue) {
        piece.x = 0.0F;
        piece.y = 0.0F;
        piece.z = 0.0F;
        piece.xRot = 0.0F;
        piece.yRot = 0.0F;
        piece.zRot = 0.0F;
        piece.visible = true;
        piece.render(poseStack, buffer.getBuffer(RenderType.armorCutoutNoCull(texture)),
                light, OverlayTexture.NO_OVERLAY, red, green, blue, 1.0F);
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
     * 原版盔甲贴图的命名规则：layer 2 是贴身的护腿图集，layer 1 是其他所有部位
     * 模组材料可能带自己的命名空间
     */
    private static ResourceLocation texture(ArmorItem armor, boolean inner, String overlay) {
        String material = armor.getMaterial().getName();
        String namespace = "minecraft";

        int colon = material.indexOf(':');
        if (colon >= 0) {
            namespace = material.substring(0, colon);
            material = material.substring(colon + 1);
        }

        String path = "textures/models/armor/" + material + "_layer_" + (inner ? 2 : 1)
                + (overlay == null ? "" : "_" + overlay) + ".png";

        ResourceLocation location = new ResourceLocation(namespace, path);
        // 材料没有对应图集时回退到皮甲贴图，而不是崩溃
        return Minecraft.getInstance().getResourceManager().getResource(location).isPresent()
                ? location
                : new ResourceLocation("minecraft", "textures/models/armor/leather_layer_1.png");
    }
}
