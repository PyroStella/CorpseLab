package com.pyrostella.corpselab.client;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.CorpseLabRegistry;
import com.pyrostella.corpselab.config.CorpseLabConfig;
import com.pyrostella.corpselab.net.RagdollGrabPacket;
import com.pyrostella.corpselab.net.CorpseLabNetwork;
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
            event.registerEntityRenderer(CorpseLabRegistry.MOB_CORPSE.get(), MobCorpseRenderer::new);
            event.registerEntityRenderer(CorpseLabRegistry.PLAN_CORPSE.get(), PlanCorpseRenderer::new);
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
