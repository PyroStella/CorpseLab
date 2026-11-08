package com.pyrostella.corpselab.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pyrostella.corpselab.client.compat.PoseCompat;
import com.pyrostella.corpselab.internal.corpse.CorpseEntity;
import com.pyrostella.corpselab.api.model.BodyPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * 画出主人的玩家模型，但把每个长方体摆在它自己刚体的变换上
 * 而不是模型本来使用的枢轴
 *
 * <p>这之所以成立，是因为布娃娃的每个可渲染长方体都对应一个刚体。这里
 * 没有模型改造、没有额外几何，也没有自定义网格 ——
 * 就是原版画的那六个部件，放到别的位置
 */
public class RagdollEntityRenderer extends EntityRenderer<CorpseEntity> {

    private final PlayerModel<AbstractClientPlayer> wideModel;
    private final PlayerModel<AbstractClientPlayer> slimModel;

    /** 原版的两套盔甲模型：细的那套用于护腿 */
    private final HumanoidModel<AbstractClientPlayer> innerArmor;
    private final HumanoidModel<AbstractClientPlayer> outerArmor;

    private final ItemInHandRenderer itemRenderer;

    public RagdollEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.wideModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false);
        this.slimModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.innerArmor = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
        this.outerArmor = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
        this.itemRenderer = context.getItemInHandRenderer();
    }

    /**
     * 主人在玩家列表里的条目，这是拿皮肤的可靠来源
     *
     * <p>按 uuid 查玩家实体会在死亡前后失效：玩家重生时，垂死的
     * 实体会被替换掉，于是尸体会退化成默认皮肤，要等主人回来
     * 才会自己纠正。玩家列表条目在两种情况下都还在
     * 主人根本不在尸体附近时它也存在
     */
    private PlayerInfo playerInfo(CorpseEntity entity) {
        UUID id = entity.getOwnerId();
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return id == null || connection == null ? null : connection.getPlayerInfo(id);
    }

    @Override
    public ResourceLocation getTextureLocation(CorpseEntity entity) {
        PlayerInfo info = playerInfo(entity);
        if (info != null) {
            return info.getSkinLocation();
        }

        AbstractClientPlayer owner = owner(entity);
        if (owner != null) {
            return owner.getSkinTextureLocation();
        }
        UUID id = entity.getOwnerId();
        return id == null ? DefaultPlayerSkin.getDefaultSkin() : DefaultPlayerSkin.getDefaultSkin(id);
    }

    private static AbstractClientPlayer owner(CorpseEntity entity) {
        UUID id = entity.getOwnerId();
        if (id == null || Minecraft.getInstance().level == null) {
            return null;
        }
        Player player = Minecraft.getInstance().level.getPlayerByUUID(id);
        return player instanceof AbstractClientPlayer client ? client : null;
    }

    private boolean isSlim(CorpseEntity entity) {
        PlayerInfo info = playerInfo(entity);
        if (info != null) {
            return "slim".equals(info.getModelName());
        }
        AbstractClientPlayer owner = owner(entity);
        if (owner != null) {
            return "slim".equals(owner.getModelName());
        }
        UUID id = entity.getOwnerId();
        return id != null && "slim".equals(DefaultPlayerSkin.getSkinModelName(id));
    }

    @Override
    public void render(CorpseEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int light) {
        if (!entity.hasPose()) {
            return;
        }

        // 位姿只解析一次，保证每条肢体和相机参考的是同一瞬间
        entity.samplePose(partialTick);

        boolean slim = isSlim(entity);
        PlayerModel<AbstractClientPlayer> model = slim ? slimModel : wideModel;
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(getTextureLocation(entity)));

        Quaternionf rotation = new Quaternionf();

        // 调度器已经把 pose stack 平移到的位置。肢体位置是世界坐标
        // 所以这里要把它减回去；这样即使实体自身的位置同步有延迟
        // 身体也仍然保持刚性
        double renderX = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double renderY = Mth.lerp(partialTick, entity.yOld, entity.getY());
        double renderZ = Mth.lerp(partialTick, entity.zOld, entity.getZ());

        BodyPart[] parts = BodyPart.values();
        for (int i = 0; i < parts.length; i++) {
            BodyPart part = parts[i];
            Vec3 world = entity.partWorldPosition(i);
            entity.partRotation(i, rotation);

            poseStack.pushPose();
            poseStack.translate(world.x - renderX, world.y - renderY, world.z - renderZ);
            PoseCompat.mulPose(poseStack, rotation);

            // 进入模型空间。原版画人形时把 X 和 Y 取反
            // 这也正是让皮肤贴图方向正确的原因
            poseStack.scale(-1.0F, -1.0F, 1.0F);

            // 把长方体自己的中心对到刚体原点上。少了这一步
            // 部件会挂在自己的枢轴外，对手臂来说枢轴就是肩膀
            Vector3f centre = cuboidCentre(part, slim);
            poseStack.translate(-centre.x / 16.0F, -centre.y / 16.0F, -centre.z / 16.0F);

            renderPart(basePart(model, part), poseStack, consumer, light);
            renderPart(overlayPart(model, part), poseStack, consumer, light);

            // 此时栈已经位于模型空间里该部件自己的枢轴
            // 而这正是原版绘制盔甲和手持物品的起点
            RagdollEquipment.render(entity, part, poseStack, buffer, light,
                    innerArmor, outerArmor);
            renderHeldItem(entity, part, poseStack, buffer, light);

            poseStack.popPose();
        }

        super.render(entity, entityYaw, partialTick, poseStack, buffer, light);
    }

    /**
     * 在手上画出主人正拿着的东西
     *
     * <p>照着原版的 ItemInHandLayer，从手臂的枢轴出发：同样的旋转
     * 同样的到拳头的偏移，物品才会握在手里而不是浮着
     */
    private void renderHeldItem(CorpseEntity entity, BodyPart part, PoseStack poseStack,
                                MultiBufferSource buffer, int light) {
        if (part != BodyPart.ARM_LEFT && part != BodyPart.ARM_RIGHT) {
            return;
        }
        AbstractClientPlayer owner = owner(entity);

        boolean leftArm = part == BodyPart.ARM_LEFT;
        // 主人离线时读不到惯用手，按常见情况处理
        HumanoidArm mainArm = owner == null ? HumanoidArm.RIGHT : owner.getMainArm();
        boolean isMainHand = (mainArm == HumanoidArm.LEFT) == leftArm;

        ItemStack stack = entity.equipment(isMainHand
                ? EquipmentSlot.MAINHAND
                : EquipmentSlot.OFFHAND);

        if (stack.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        poseStack.mulPose(new com.mojang.math.Vector3f(1.0F, 0.0F, 0.0F).rotationDegrees(-90.0F));
        poseStack.mulPose(new com.mojang.math.Vector3f(0.0F, 1.0F, 0.0F).rotationDegrees(180.0F));
        poseStack.translate((leftArm ? -1.0F : 1.0F) / 16.0F, 0.125F, -0.625F);

        itemRenderer.renderItem(owner != null ? owner : Minecraft.getInstance().player, stack,
                leftArm ? ItemTransforms.TransformType.THIRD_PERSON_LEFT_HAND
                        : ItemTransforms.TransformType.THIRD_PERSON_RIGHT_HAND,
                leftArm, poseStack, buffer, light);

        poseStack.popPose();
    }

    /** 把模型自身的姿态清零，让方块落在物理计算给出的位置 */
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

    private static ModelPart basePart(PlayerModel<AbstractClientPlayer> model, BodyPart part) {
        return switch (part) {
            case HEAD -> model.head;
            case TORSO -> model.body;
            case ARM_LEFT -> model.leftArm;
            case ARM_RIGHT -> model.rightArm;
            case LEG_LEFT -> model.leftLeg;
            case LEG_RIGHT -> model.rightLeg;
        };
    }

    /** 外层的皮肤层：头发、外套、袖子、裤腿 */
    private static ModelPart overlayPart(PlayerModel<AbstractClientPlayer> model, BodyPart part) {
        return switch (part) {
            case HEAD -> model.hat;
            case TORSO -> model.jacket;
            case ARM_LEFT -> model.leftSleeve;
            case ARM_RIGHT -> model.rightSleeve;
            case LEG_LEFT -> model.leftPants;
            case LEG_RIGHT -> model.rightPants;
        };
    }

    /**
     * 每个长方体在自己部件局部空间里的中心，单位是模型像素
     *
     * <p>这些都是原版人形模型的常量。头部方块相对枢轴的 y 范围
     * 是 -8..0，躯干是 0..12，手臂和腿分别是 -2..10 和 0..12
     * 细模型的胳膊宽三像素而不是四像素，所以它们的中心
     * 会偏半个像素
     */
    private static Vector3f cuboidCentre(BodyPart part, boolean slim) {
        return switch (part) {
            case HEAD -> new Vector3f(0.0F, -4.0F, 0.0F);
            case TORSO -> new Vector3f(0.0F, 6.0F, 0.0F);
            case ARM_LEFT -> new Vector3f(slim ? 0.5F : 1.0F, 4.0F, 0.0F);
            case ARM_RIGHT -> new Vector3f(slim ? -0.5F : -1.0F, 4.0F, 0.0F);
            case LEG_LEFT, LEG_RIGHT -> new Vector3f(0.0F, 6.0F, 0.0F);
        };
    }
}
