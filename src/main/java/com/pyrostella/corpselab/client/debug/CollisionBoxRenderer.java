package com.pyrostella.corpselab.client.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.internal.config.CorpseLabConfig;
import com.pyrostella.corpselab.internal.corpse.PlanCorpseEntity;
import com.pyrostella.corpselab.client.animation.PoseBuffer;
import com.pyrostella.corpselab.internal.corpse.RagdollEntity;
import com.pyrostella.corpselab.api.model.BodyPlan;
import com.pyrostella.corpselab.internal.skeleton.PlanRagdoll;
import com.pyrostella.corpselab.api.model.BodyPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 调试视图：把物理求解器里真正在用的盒子画成线框
 *
 * <p>抽搐这种问题，看不出"谁推谁"就只能猜，所以这里照着解算器读的同一份数据画：每个刚体
 * 一个绿框，自碰撞的球用青色画成三个正交大圆。盒子尺寸、朝向、球的位置全部取自服务端
 * 同步下来的位姿，和渲染出来的模型是同一个变换，所以框和模型应当严丝合缝。</p>
 *
 * <p>用的是原版 {@code RenderType.lines()} 加 {@link LevelRenderer#renderLineBox}
 * 也就是 F3+B 碰撞箱那条路径：顶点格式、法线、批次刷新都交给原版，不自己拼。</p>
 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID, value = Dist.CLIENT)
public final class CollisionBoxRenderer {

    /** 只画这个半径内的尸体：更远处的尸体在冻结，看了也没用 */
    private static final double RANGE = 48.0D;

    /** 线框比盒子大一圈：线正好压在模型表面上时会被深度测试切成一闪一闪的 */
    private static final double INFLATE = 0.01D;

    /** 自碰撞球画成圆时的分段数 */
    private static final int CIRCLE_SEGMENTS = 16;

    private static final float PLAN_RED = 0.30F;
    private static final float PLAN_GREEN = 1.00F;
    private static final float PLAN_BLUE = 0.45F;
    private static final float SPHERE_RED = 0.35F;
    private static final float SPHERE_GREEN = 0.75F;
    private static final float SPHERE_BLUE = 1.00F;
    private static final float RAGDOLL_RED = 1.00F;
    private static final float RAGDOLL_GREEN = 0.80F;
    private static final float RAGDOLL_BLUE = 0.30F;

    /** 打开后只汇报一次，免得每帧刷日志 */
    private static boolean reported;

    private CollisionBoxRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        if (!CorpseLabConfig.CLIENT_SPEC.isLoaded() || !CorpseLabConfig.CLIENT.collisionBoxDisplay.get()) {
            reported = false;
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // entitiesForRendering() 只存在于客户端关卡：这是纯客户端的调试视图
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }

        Vec3 eye = event.getCamera().getPosition();
        float partialTick = event.getPartialTick();
        PoseStack poseStack = event.getPoseStack();

        // 关卡渲染用的就是这个 BufferSource，刷批次的时机也和原版画碰撞箱时一样
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        int drawn = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.distanceToSqr(eye.x, eye.y, eye.z) > RANGE * RANGE) {
                continue;
            }
            if (entity instanceof PlanCorpseEntity corpse) {
                drawn += drawPlanCorpse(poseStack, lines, corpse, eye, partialTick);
            } else if (entity instanceof RagdollEntity ragdoll) {
                drawn += drawRagdoll(poseStack, lines, ragdoll, eye, partialTick);
            }
        }

        buffers.endBatch(RenderType.lines());

        if (drawn > 0 && !reported) {
            reported = true;
            CorpseLab.LOGGER.info("Collision boxes: drawing {} bodies", drawn);
        }
    }

    /** @return 画了几个刚体 */
    private static int drawPlanCorpse(PoseStack poseStack, VertexConsumer lines, PlanCorpseEntity entity,
                                      Vec3 eye, float partialTick) {
        PoseBuffer poses = entity.poses();
        BodyPlan plan = entity.plan();
        if (poses == null || !poses.hasPose() || plan.boxCount() == 0) {
            return 0;
        }
        poses.sample(partialTick);

        Quaternionf rotation = new Quaternionf();
        Vector3f corner = new Vector3f();
        int drawn = 0;
        for (BodyPlan.Box box : plan.boxes()) {
            Vec3 centre = poses.position(box.index());
            poses.rotation(box.index(), rotation);

            drawBox(poseStack, lines, centre, eye, rotation,
                    box.hx() + INFLATE, box.hy() + INFLATE, box.hz() + INFLATE,
                    PLAN_RED, PLAN_GREEN, PLAN_BLUE);

            // 同一个盒子的自碰撞球：位置和半径都取自构造时用的那份数据
            PlanRagdoll.CollisionSpheres spheres = PlanRagdoll.collisionSpheres(box);
            for (Vec3 offset : spheres.offsets()) {
                corner.set((float) offset.x, (float) offset.y, (float) offset.z);
                rotation.transform(corner);
                drawSphere(poseStack, lines, centre.add(corner.x, corner.y, corner.z), eye,
                        spheres.radius(), SPHERE_RED, SPHERE_GREEN, SPHERE_BLUE);
            }
            drawn++;
        }
        return drawn;
    }

    /** @return 画了几个刚体 */
    private static int drawRagdoll(PoseStack poseStack, VertexConsumer lines, RagdollEntity entity,
                                   Vec3 eye, float partialTick) {
        if (!entity.hasPose()) {
            return 0;
        }
        entity.samplePose(partialTick);

        Quaternionf rotation = new Quaternionf();
        BodyPart[] parts = BodyPart.values();
        for (int i = 0; i < parts.length; i++) {
            Vec3 centre = entity.partWorldPosition(i);
            entity.partRotation(i, rotation);
            BodyPart part = parts[i];
            drawBox(poseStack, lines, centre, eye, rotation,
                    part.halfWidth + INFLATE, part.halfHeight + INFLATE, part.halfDepth + INFLATE,
                    RAGDOLL_RED, RAGDOLL_GREEN, RAGDOLL_BLUE);
        }
        return parts.length;
    }

    private static void drawBox(PoseStack poseStack, VertexConsumer lines, Vec3 centre, Vec3 eye,
                                Quaternionf rotation, double hx, double hy, double hz,
                                float red, float green, float blue) {
        poseStack.pushPose();
        // 位姿栈的原点在摄像机上，所以世界坐标要减去相机位置
        poseStack.translate(centre.x - eye.x, centre.y - eye.y, centre.z - eye.z);
        poseStack.mulPose(rotation);
        LevelRenderer.renderLineBox(poseStack, lines, -hx, -hy, -hz, hx, hy, hz, red, green, blue, 1.0F);
        poseStack.popPose();
    }

    private static void drawSphere(PoseStack poseStack, VertexConsumer lines, Vec3 centre, Vec3 eye,
                                   double radius, float red, float green, float blue) {
        poseStack.pushPose();
        poseStack.translate(centre.x - eye.x, centre.y - eye.y, centre.z - eye.z);
        PoseStack.Pose pose = poseStack.last();
        circle(pose, lines, radius, 0, 1, red, green, blue);
        circle(pose, lines, radius, 1, 2, red, green, blue);
        circle(pose, lines, radius, 2, 0, red, green, blue);
        poseStack.popPose();
    }

    /** 在 axisA/axisB 张成的平面里画一个半径 radius 的圆，落在原点 */
    private static void circle(PoseStack.Pose pose, VertexConsumer lines, double radius,
                               int axisA, int axisB, float red, float green, float blue) {
        float lastX = 0.0F;
        float lastY = 0.0F;
        float lastZ = 0.0F;
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double angle = Math.PI * 2.0D * i / CIRCLE_SEGMENTS;
            float u = (float) (Math.cos(angle) * radius);
            float v = (float) (Math.sin(angle) * radius);
            float x = axisA == 0 ? u : (axisB == 0 ? v : 0.0F);
            float y = axisA == 1 ? u : (axisB == 1 ? v : 0.0F);
            float z = axisA == 2 ? u : (axisB == 2 ? v : 0.0F);
            if (i > 0) {
                segment(pose, lines, lastX, lastY, lastZ, x, y, z, red, green, blue);
            }
            lastX = x;
            lastY = y;
            lastZ = z;
        }
    }

    private static void segment(PoseStack.Pose pose, VertexConsumer lines,
                                float ax, float ay, float az, float bx, float by, float bz,
                                float red, float green, float blue) {
        Vector3f normal = new Vector3f(bx - ax, by - ay, bz - az);
        if (normal.lengthSquared() > 1.0E-8F) {
            normal.normalize();
        } else {
            normal.set(0.0F, 1.0F, 0.0F);
        }
        lines.vertex(pose.pose(), ax, ay, az)
                .color(red, green, blue, 1.0F)
                .normal(pose.normal(), normal.x, normal.y, normal.z)
                .endVertex();
        lines.vertex(pose.pose(), bx, by, bz)
                .color(red, green, blue, 1.0F)
                .normal(pose.normal(), normal.x, normal.y, normal.z)
                .endVertex();
    }
}
