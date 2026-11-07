package com.pyrostella.corpselab.entity;

import com.pyrostella.corpselab.config.CorpseLabConfig;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 被留下的身体：它落下、停稳，然后就再也不产生任何开销
 *
 * <p>玩家尸体和生物尸体共用这段逻辑，两者的区别只在于携带的
 * 东西和存在的时长。有意思的地方是冻结：模拟一具已经停止移动
 * 的身体纯属浪费，但一具<em>再也</em>动不了的身体就是布景而
 * 不是布娃娃，所以被冻结的身体仍会时不时检查一下它躺着的
 * 那块地面还在不在
 */
public abstract class SettlingBody extends RagdollEntity {

    /**
     * 冻结的身体多久检查一次自己是否还有支撑。每秒走一步的代价是
     * 完整模拟的二十分之一，而且比任何人挖掉方块都快
     */
    private static final int WAKE_CHECK_INTERVAL = 20;

    private boolean frozen;
    private int settledTicks;
    private int wakeCheck;
    private int age;

    protected SettlingBody(EntityType<? extends SettlingBody> type, Level level) {
        super(type, level);
    }

    public int age() {
        return age;
    }

    protected void setAge(int age) {
        this.age = age;
    }

    public boolean isFrozen() {
        return frozen;
    }

    protected void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    @Override
    protected void serverTick() {
        age++;

        // 被拖动也算运动，所以被手抓着的身体不能在别人手里冻住
        if (isGrabbed()) {
            wake();
        }

        if (frozen) {
            checkStillSupported();
        } else {
            applyGrab();
            stepPhysics();
            sendPose();

            if (!isGrabbed()
                    && skeleton().isSettled(CorpseLabConfig.SERVER.releaseSpeedThreshold.get())) {
                settledTicks++;
                if (settledTicks >= CorpseLabConfig.SERVER.corpseFreezeTicks.get()) {
                    frozen = true;
                }
            } else {
                settledTicks = 0;
            }
        }

        checkLifetime();
    }

    /** 隔一段时间走一步，看它原先躺着的地面还在不在 */
    private void checkStillSupported() {
        if (++wakeCheck < WAKE_CHECK_INTERVAL) {
            return;
        }
        wakeCheck = 0;

        stepPhysics();
        sendPose();

        if (!skeleton().isGrounded()) {
            wake();
        }
    }

    /** 把冻结的身体重新纳入模拟，用于任何打扰到它的情况 */
    public void wake() {
        frozen = false;
        settledTicks = 0;
    }

    @Override
    public void wakeForGrab() {
        wake();
    }

    /** 抛出身体并唤醒它，这样爆炸能像推动活人一样推动它 */
    public void launchAndWake(Vec3 velocity) {
        wake();
        launch(velocity);
    }

    /** 决定这具身体什么时候消失。每个 tick 调用一次 */
    protected abstract void checkLifetime();

    @Override
    public boolean shouldBeSaved() {
        return true;
    }

    /**
     * 用包住整具身体的盒子，而不是注册时给的那个立方体
     *
     * <p>这个 entity 位于质心，但玩家瞄准的是自己看得见的肢体
     * 而这些肢体会摊到质心两侧各一个方块左右
     */
    @Override
    protected AABB makeBoundingBox() {
        return new AABB(getX() - 0.7D, getY() - 0.5D, getZ() - 0.7D,
                getX() + 0.7D, getY() + 0.7D, getZ() + 0.7D);
    }
}
