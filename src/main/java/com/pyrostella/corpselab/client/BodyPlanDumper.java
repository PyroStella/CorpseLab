package com.pyrostella.corpselab.client;

import java.util.HashSet;
import java.util.Set;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.net.CorpseLabNetwork;
import com.pyrostella.corpselab.net.PlanSubmitPacket;
import com.pyrostella.corpselab.plan.BodyPlan;
import com.pyrostella.corpselab.plan.ModelPartScanner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 调试探针：第一次看到某种生物时，把它的 BodyPlan 拆出来打到日志和聊天栏
 *
 * <p>不用指令、不用按键——走过去看一眼就行。每种生物只打一次，避免刷屏。</p>
 *
 * <p>它验证的是"能不能从原版模型拆出合理的盒子与关节"，还不会真的生成尸体。</p>
 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID, value = Dist.CLIENT)
public final class BodyPlanDumper {

    /** 已经打印过的生物类型 */
    private static final Set<ResourceLocation> SEEN = new HashSet<>();

    private BodyPlanDumper() {
    }

    @SubscribeEvent
    @SuppressWarnings("rawtypes")
    public static void onRenderLiving(RenderLivingEvent.Pre event) {
        LivingEntity entity = event.getEntity();
        EntityType<?> type = entity.getType();
        ResourceLocation id = EntityType.getKey(type);

        if (!SEEN.add(id)) {
            return;
        }

        EntityRenderer renderer = event.getRenderer();
        if (!(renderer instanceof LivingEntityRenderer<?, ?> livingRenderer)) {
            return;
        }

        EntityModel<?> model = livingRenderer.getModel();
        BodyPlan plan = ModelPartScanner.scan(model);

        CorpseLab.LOGGER.info("BodyPlan {} [{}]\n{}",
                id, model.getClass().getSimpleName(), plan.describe());

        // 上报给服务端缓存，之后这个类型的生物死亡时就能留下尸体
        if (plan.boxCount() > 0) {
            CorpseLabNetwork.CHANNEL.sendToServer(new PlanSubmitPacket(id, plan));
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(
                    "§b[CorpseLab] " + id.getPath() + " §7[" + model.getClass().getSimpleName() + "] §f" + plan.boxCount() + " 盒 / " + plan.jointCount() + " 关节"), false);
        }
    }
}
