package com.pyrostella.corpselab.client;

import com.pyrostella.corpselab.config.CorpseLabConfig;
import com.pyrostella.corpselab.entity.RagdollEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * 一行提示，说明倒地玩家还能做什么
 *
 * <p>刻意做成原版风格：字体用 Minecraft 自己的，GUI 缩放也用游戏自己的
 * 同时不做矩阵缩放。文字只有按 1:1 画进图集当初为它设定的空间时才清晰。为了让字更大
 * 去缩放位姿栈，恰恰是让模组 HUD 在原版旁边
 * 显得发糊的原因
 *
 * <p>任何骑乘者原版都会画一行“Press Left Shift to Dismount”，所以这条提示
 * 叠在它上方而不重复，并点出原版无从知晓的那部分
 */
public final class ControlsHintOverlay implements IGuiOverlay {

    public static final ControlsHintOverlay INSTANCE = new ControlsHintOverlay();

    /**
     * 避开快捷栏，更重要的是避开原版自己的下马提示
     *
     * <p>原版那条提示画在距底部约 74 的位置，取 75 时两行会挤进一个像素之内
     * 双双变得无法阅读。这是对着截图量出来的，这类重叠也只有
     * 这种方式才看得出来
     */
    private static final int BOTTOM_OFFSET = 88;

    private static final int TEXT_COLOUR = 0xFFFFFFFF;

    private ControlsHintOverlay() {
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick,
                       int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        if (!(minecraft.player.getVehicle() instanceof RagdollEntity ragdoll)) {
            return;
        }
        if (!CorpseLabConfig.CLIENT.showControlsHint.get()) {
            return;
        }

        // 给出一个服务端会忽略的按键，比什么都不说更糟，所以这行提示
        // 只在起身真的会生效时才提到它
        Component line = ragdoll.canGetUpSynced()
                ? Component.translatable("corpselab.hud.controls")
                : Component.translatable("corpselab.hud.controls.down");

        // 只用整数坐标。半个像素的偏移就足以让每个字形都发虚
        graphics.drawCenteredString(minecraft.font, line,
                screenWidth / 2, screenHeight - BOTTOM_OFFSET, TEXT_COLOUR);
    }
}
