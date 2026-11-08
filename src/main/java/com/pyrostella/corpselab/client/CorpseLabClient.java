package com.pyrostella.corpselab.client;

import com.pyrostella.corpselab.client.input.CorpseLabKeybinds;
import com.pyrostella.corpselab.client.render.PlanCorpseRenderer;
import com.pyrostella.corpselab.client.render.RagdollEntityRenderer;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.internal.registry.CorpseLabRegistry;
import com.pyrostella.corpselab.internal.config.CorpseLabConfig;
import com.pyrostella.corpselab.internal.corpse.CorpseInteraction;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.client.event.InputEvent;
import com.pyrostella.corpselab.internal.network.RagdollGrabPacket;
import com.pyrostella.corpselab.internal.network.CorpseLabNetwork;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

public final class CorpseLabClient {

    private CorpseLabClient() {
    }

    /** Mod 总线注册：按键绑定与渲染器 */
    @Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Setup {

        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(CorpseLabKeybinds.GRAB);
        }

        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(CorpseLabRegistry.CORPSE.get(), RagdollEntityRenderer::new);
            event.registerEntityRenderer(CorpseLabRegistry.PLAN_CORPSE.get(), PlanCorpseRenderer::new);
            event.registerEntityRenderer(CorpseLabRegistry.CORPSE_LIMB.get(),
                    net.minecraft.client.renderer.entity.NoopRenderer::new);
        }
    }

    /** Forge 总线的玩法钩子 */
    @Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID, value = Dist.CLIENT)
    public static final class Hooks {

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.level == null) {
                return;
            }

            reportGrab();
        }

        /** 上一 tick 抓取键是否按下，这样只发送变化 */
        private static boolean grabbing;

        /** 世界或连接结束时清除按键边沿，新世界按当前按键重新上报 */
        public static void resetGrab() {
            grabbing = false;
        }

        /**
         * 抓取是按住生效的键，但每 tick 都发太浪费，所以只有按下
         * 和松开这两个变化会走网络
         */
        private static void reportGrab() {
            boolean down = CorpseLabKeybinds.GRAB.isDown();
            if (down == grabbing) {
                return;
            }
            grabbing = down;
            CorpseLabNetwork.CHANNEL.sendToServer(new RagdollGrabPacket(down));
        }

        @SubscribeEvent
        public static void onUseItem(InputEvent.InteractionKeyMappingTriggered event) {
            if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
                return;
            }
            var hit = CorpseInteraction.pickLootTarget(minecraft.player,
                    minecraft.player.getAttributeValue(ForgeMod.ATTACK_RANGE.get()));
            if (hit == null) {
                return;
            }
            // 继续使用原版交互包和服务端搜刮入口，不改变全局准星目标
            var result = minecraft.gameMode.interactAt(minecraft.player, hit.getEntity(), hit, event.getHand());
            if (!result.consumesAction()) {
                result = minecraft.gameMode.interact(minecraft.player, hit.getEntity(), event.getHand());
            }
            if (result.consumesAction()) {
                event.setCanceled(true);
                event.setSwingHand(result.shouldSwing());
            }
        }

        @SubscribeEvent
        public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
            // 原版会继续为死亡动画绘制正在倒下的玩家，这一幕正好落在尸体上
            // 于是同一处出现两具身体，一具还在穿过另一具。现在尸体本身
            // 就是死亡动画
            if (event.getEntity().deathTime > 0
                    && CorpseLabConfig.CLIENT.hideVanillaDeathAnimation.get()) {
                event.setCanceled(true);
            }
        }
    }
}
