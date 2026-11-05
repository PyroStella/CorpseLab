package com.pyrostella.corpselab.plan;

import java.util.Arrays;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * 死亡那一刻，模型各部件<b>正在做的动作</b>，折算成每个盒子的模型空间位姿。
 *
 * <p>和 {@code ragdoll.LimbPose} 是同一个思路：布娃娃一开始就躺成“站着不动”的样子，
 * 看起来像换了一个人凭空出现；照着原版 {@code setupAnim} 的公式重算一遍，
 * 尸体就会保持奔跑、张望、下蹲的姿势摔出去。</p>
 *
 * <p>区别在于这里没有写死的人形部件表：部件按路径里的关键词分类
 * （{@code head} / {@code arm} / {@code leg} / {@code body}），所以僵尸、骷髅、四足生物都适用。
 * 姿势按原版层级累加：父部件的旋转会带动子部件，{@code hat} 才会跟着头转。</p>
 *
 * <p>单位：模型空间，原点在脚底，+Y 向上，数字是方块。旋转也用模型空间的轴，
 * 交给 {@link PlanRagdoll} 时再做 Y 翻转。</p>
 */
public final class PlanPose {

    /** 模型像素 → 方块 */
    private static final double PIXEL = 1.0D / 16.0D;

    /** 走路的摆动频率，来自原版 {@code setupAnim} */
    private static final double SWING_SPEED = 0.6662D;

    /**
     * 一个盒子的姿势：绕自己枢轴的旋转 + 枢轴在世界里的新位置。
     *
     * @param rot    模型空间累计旋转
     * @param origin 模型空间枢轴位置（已含父部件旋转与下蹲位移）
     */
    public record Frame(Quaterniond rot, Vector3d origin) {
    }

    private PlanPose() {
    }

    /**
     * 算出每个盒子的姿势。
     *
     * @param plan   骨架
     * @param entity 死者；为 {@code null} 时返回零姿态（存档重建就走这条路，姿势已经被物理记住了）
     */
    public static Frame[] frames(BodyPlan plan, LivingEntity entity) {
        int count = plan.boxCount();
        Frame[] frames = new Frame[count];

        int[] parent = new int[count];
        Arrays.fill(parent, -1);
        for (BodyPlan.Joint joint : plan.joints()) {
            parent[joint.child()] = joint.parent();
        }

        double[] pitch = new double[count];
        double[] yaw = new double[count];
        double[] shiftX = new double[count];
        double[] shiftY = new double[count];
        double[] shiftZ = new double[count];

        if (entity != null) {
            pose(plan, entity, pitch, yaw, shiftX, shiftY, shiftZ);
        }

        for (int i = 0; i < count; i++) {
            resolve(i, plan, parent, pitch, yaw, shiftX, shiftY, shiftZ, frames);
        }
        return frames;
    }

    /** 把原版动画公式套到每个盒子上（角度为弧度，位移为方块） */
    private static void pose(BodyPlan plan, LivingEntity entity,
                             double[] pitch, double[] yaw,
                             double[] shiftX, double[] shiftY, double[] shiftZ) {
        float limbSwing = entity.walkAnimation.position();
        double amount = entity.walkAnimation.speed();

        // 摆动项：原版各处都是 cos(limbSwing * 0.6662) * amount，只是幅度和相位不同
        double swing = Math.cos(limbSwing * SWING_SPEED) * amount;

        boolean crouching = entity.isCrouching();
        // 头部：yaw 是相对身体的偏转（原版就是这么喂给 head.yRot 的）
        double headYaw = Math.toRadians(Mth.wrapDegrees(entity.getYHeadRot() - entity.yBodyRot));
        double headPitch = Math.toRadians(entity.getXRot());

        for (int i = 0; i < plan.boxCount(); i++) {
            String leaf = leaf(plan.boxes().get(i).path());
            boolean left = leaf.contains("left");
            boolean front = leaf.contains("front");
            boolean hind = leaf.contains("hind") || leaf.contains("back");

            if (leaf.contains("head") || leaf.contains("hat") || leaf.contains("skull")) {
                yaw[i] = headYaw;
                pitch[i] = headPitch;
                if (crouching) {
                    shiftY[i] = 4.2D * PIXEL;
                }
            } else if (leaf.contains("arm") || leaf.contains("hand") || leaf.contains("wing")) {
                // HumanoidModel：rightArm = cos(+π) * 2 * amount * 0.5，leftArm 相位相反
                pitch[i] = (left ? swing : -swing) + (crouching ? 0.4D : 0.0D);
                if (crouching) {
                    shiftY[i] = 3.2D * PIXEL;
                }
            } else if (leaf.contains("leg") || leaf.contains("foot") || leaf.contains("thigh")) {
                // 人形：右腿 cos、左腿 cos(+π)。四足是斜对角同步：右后/左前 cos，右前/左后 cos(+π)
                boolean positive = (front || hind) ? (hind != left) : !left;
                pitch[i] = (positive ? 1.4D : -1.4D) * swing;
                if (crouching) {
                    shiftY[i] = 0.2D * PIXEL;
                    shiftZ[i] = 4.0D * PIXEL;
                }
            } else if (leaf.contains("body") || leaf.contains("torso") || leaf.contains("chest")) {
                pitch[i] = crouching ? 0.5D : 0.0D;
                if (crouching) {
                    shiftY[i] = 3.2D * PIXEL;
                }
            }
        }
    }

    /** 递归累加父部件的旋转与位移；父部件总在子部件前面，所以缓存一次就够 */
    private static Frame resolve(int index, BodyPlan plan, int[] parent,
                                 double[] pitch, double[] yaw,
                                 double[] shiftX, double[] shiftY, double[] shiftZ,
                                 Frame[] frames) {
        Frame cached = frames[index];
        if (cached != null) {
            return cached;
        }

        BodyPlan.Box box = plan.boxes().get(index);
        Quaterniond local = new Quaterniond().rotateY(yaw[index]).rotateX(pitch[index]);
        Vector3d pivot = new Vector3d(box.px(), box.py(), box.pz());
        Vector3d shift = new Vector3d(shiftX[index], shiftY[index], shiftZ[index]);

        Frame frame;
        int par = parent[index];
        if (par < 0) {
            frame = new Frame(local, pivot.add(shift));
        } else {
            Frame parentFrame = resolve(par, plan, parent, pitch, yaw, shiftX, shiftY, shiftZ, frames);
            BodyPlan.Box parentBox = plan.boxes().get(par);

            // 相对父枢轴的平移，先被父部件旋转，再叠到父枢轴上
            Vector3d translation = new Vector3d(pivot)
                    .sub(parentBox.px(), parentBox.py(), parentBox.pz())
                    .add(shift);
            parentFrame.rot().transform(translation);

            frame = new Frame(
                    new Quaterniond(parentFrame.rot()).mul(local),
                    new Vector3d(parentFrame.origin()).add(translation));
        }

        frames[index] = frame;
        return frame;
    }

    /** 路径最后一段，例如 {@code body.rightArm} → {@code rightarm} */
    private static String leaf(String path) {
        int dot = path.lastIndexOf('.');
        String leaf = dot < 0 ? path : path.substring(dot + 1);
        return leaf.toLowerCase(java.util.Locale.ROOT);
    }
}
