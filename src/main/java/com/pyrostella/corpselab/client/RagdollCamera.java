package com.pyrostella.corpselab.client;

import com.pyrostella.corpselab.config.CorpseLabConfig;
import com.pyrostella.corpselab.entity.RagdollEntity;
import com.pyrostella.corpselab.ragdoll.BodyPart;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ViewportEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 把第一人称相机放进布娃娃的头部
 *
 * <p>没有它，瘫倒后视角会悬在站立时的眼高上、浮在身体上方
 * 身体在下面翻滚，而世界始终是平的
 * 这看起来像是在旁观自己的布娃娃，而不是成为它
 *
 * <p>Minecraft 的相机旋转是先绕 Y 的 yaw、再绕 X 的 pitch、最后绕 Z 的 roll
 * 这正好是头部朝向的 YXZ 欧拉分解，所以头部的四元数
 * 可以直接换算成事件接受的三个角
 * 位置则要靠 access transformer，因为事件只暴露角度、不暴露位置
 *
 * <p>头部是链条末端一个又小又轻的刚体，把相机硬绑上去
 * 会把解算的每一次抖动都传进来
 * 所以位置和朝向都是向头部缓动，而不是直接吸附
 * 缓动以每秒的速率表示，这样结果就不会随帧率变化
 */
public final class RagdollCamera {

    private static final int HEAD = BodyPart.HEAD.ordinal();

    /** 缓动速率，单位是每秒，对应平滑设置的两个极端取值 */
    private static final double RATE_RAW = 60.0D;
    private static final double RATE_SMOOTH = 7.0D;

    /** 位置比朝向跟得更紧；偏离身体看起来更糟 */
    private static final double POSITION_RATE_SCALE = 1.8D;

    /** 防止卡顿或加载停滞后出现一次过大的缓动步长 */
    private static final double MAX_FRAME_SECONDS = 0.1D;

    private static final Vec3 UNSET = new Vec3(Double.NaN, 0.0D, 0.0D);

    private static Vec3 smoothedPosition = UNSET;
    private static final Quaternionf smoothedRotation = new Quaternionf();
    private static int smoothedFor = -1;
    private static long lastFrameNanos;

    private RagdollCamera() {
    }

    /** 本地玩家正透过自己布娃娃的头部看东西时为 true */
    public static boolean isActive() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.options.getCameraType().isFirstPerson()) {
            return false;
        }
        if (!CorpseLabConfig.CLIENT.firstPersonRagdollCamera.get()) {
            return false;
        }
        return minecraft.player.getVehicle() instanceof RagdollEntity ragdoll && ragdoll.hasPose();
    }

    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!isActive()) {
            reset();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        RagdollEntity ragdoll = (RagdollEntity) minecraft.player.getVehicle();

        ragdoll.samplePose((float) event.getPartialTick());

        Vec3 target = ragdoll.partWorldPosition(HEAD);
        Quaternionf headRotation = ragdoll.partRotation(HEAD, new Quaternionf());

        // 新布娃娃的第一帧直接吸附过去，这样相机不会从上一次
        // 结束的位置一路扫过来
        if (smoothedFor != ragdoll.getId() || Double.isNaN(smoothedPosition.x)) {
            smoothedFor = ragdoll.getId();
            smoothedPosition = target;
            smoothedRotation.set(headRotation);
            lastFrameNanos = System.nanoTime();
        } else {
            double seconds = frameSeconds();
            double rate = easingRate();

            double positionAlpha = alpha(rate * POSITION_RATE_SCALE, seconds);
            smoothedPosition = smoothedPosition.add(target.subtract(smoothedPosition).scale(positionAlpha));

            smoothedRotation.slerp(headRotation, (float) alpha(rate, seconds));
            smoothedRotation.normalize();
        }

        event.getCamera().setPosition(smoothedPosition);

        // 头部沿自身 -Z 看，因为原版人形模型朝 -Z
        // 而身体朝向里已经带了原版的半圈转向
        // 相机沿 +Z 看，所以在分解之前要先把头部转半圈
        // 否则视线就会从后脑勺朝外看
        Quaternionf view = new Quaternionf(smoothedRotation).rotateY((float) Math.PI);

        // Minecraft 的相机是按 RotY(-yaw) * RotX(pitch) * RotZ(roll) 构建的
        // 所以 YXZ 分解可以直接对应过去，只要把 yaw 取负
        Vector3f euler = view.getEulerAnglesYXZ(new Vector3f());
        event.setYaw((float) -Math.toDegrees(euler.y));
        event.setPitch((float) Math.toDegrees(euler.x));

        // roll 是最容易让人晕的那个成分，所以按比例缩放
        // 而不是整个套用
        event.setRoll((float) (Math.toDegrees(euler.z) * CorpseLabConfig.CLIENT.cameraRoll.get()));
    }

    /** 朝目标做指数缓动：天然与帧率无关 */
    private static double alpha(double rate, double seconds) {
        return 1.0D - Math.exp(-rate * seconds);
    }

    private static double easingRate() {
        double smoothness = CorpseLabConfig.CLIENT.cameraSmoothing.get();
        return RATE_RAW + (RATE_SMOOTH - RATE_RAW) * smoothness;
    }

    private static double frameSeconds() {
        long now = System.nanoTime();
        double seconds = (now - lastFrameNanos) / 1.0e9D;
        lastFrameNanos = now;
        return Math.min(Math.max(seconds, 0.0D), MAX_FRAME_SECONDS);
    }

    private static void reset() {
        smoothedPosition = UNSET;
        smoothedFor = -1;
    }
}
