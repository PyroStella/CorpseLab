package com.pyrostella.corpselab.client.gametest;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.CorpseLabRegistry;
import com.pyrostella.corpselab.client.model.CorpseModelAdapters;
import com.pyrostella.corpselab.entity.PlanCorpseEntity;
import com.pyrostella.corpselab.plan.BodyPlan;
import com.pyrostella.corpselab.plan.CorpseAppearance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;

/** 可选客户端集成自检，只记录顶点，不向世界添加实体或向屏幕绘制测试模型 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID, value = Dist.CLIENT)
public final class CorpseLayerRenderTests {
    private static boolean ran;

    @SubscribeEvent
    public static void onFrame(RenderLevelStageEvent event) {
        if (ran || !Boolean.getBoolean("corpselab.renderTest")
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        ran = true;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            Villager farmer = EntityType.VILLAGER.create(minecraft.level);
            farmer.setVillagerData(new VillagerData(VillagerType.SAVANNA, VillagerProfession.FARMER, 5));
            RecordingBuffers farmerBuffers = render(farmer);
            require(farmerBuffers.has("villager/type/savanna"), "村民地区贴图未输出顶点");
            require(farmerBuffers.has("villager/profession/farmer"), "村民职业贴图未输出顶点");
            require(farmerBuffers.has("villager/profession_level/diamond"), "村民等级贴图未输出顶点");

            Villager librarian = EntityType.VILLAGER.create(minecraft.level);
            librarian.setVillagerData(new VillagerData(VillagerType.SNOW, VillagerProfession.LIBRARIAN, 2));
            RecordingBuffers librarianBuffers = render(librarian);
            require(librarianBuffers.has("villager/profession/librarian")
                    && !librarianBuffers.has("villager/profession/farmer"), "同类尸体职业串用");

            Sheep sheep = EntityType.SHEEP.create(minecraft.level);
            sheep.setColor(DyeColor.RED);
            require(render(sheep).has("sheep_fur"), "羊毛独立模型未输出顶点");
            sheep.setSheared(true);
            require(!render(sheep).has("sheep_fur"), "剪毛状态未保留");

            Zombie zombie = EntityType.ZOMBIE.create(minecraft.level);
            zombie.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            require(render(zombie).has("diamond_layer_1"), "护甲独立模型未输出顶点");

            Spider spider = EntityType.SPIDER.create(minecraft.level);
            require(render(spider).has("spider_eyes"), "发光眼睛层未输出顶点");
            CorpseLab.LOGGER.info("尸体多层材质客户端自检通过：村民地区/职业/等级、独立外观、羊毛/剪毛、护甲、眼睛");
        } catch (Throwable failure) {
            CorpseLab.LOGGER.error("尸体多层材质客户端自检失败", failure);
        }
    }

    static RecordingBuffers render(LivingEntity source) {
        Minecraft minecraft = Minecraft.getInstance();
        var renderer = minecraft.getEntityRenderDispatcher().getRenderer(source);
        var adapter = CorpseModelAdapters.select(source, renderer);
        BodyPlan plan = adapter.scan(source, renderer).capture(source, renderer, 0);
        PlanCorpseEntity corpse = CorpseLabRegistry.PLAN_CORPSE.get().create(minecraft.level);
        corpse.setSourceType(EntityType.getKey(source.getType()));
        corpse.setAppearance(CorpseAppearance.capture(source));
        corpse.setPlan(plan);
        Vec3[] positions = new Vec3[plan.boxCount()];
        Quaternionf[] rotations = new Quaternionf[plan.boxCount()];
        for (BodyPlan.Box box : plan.boxes()) {
            positions[box.index()] = new Vec3(-box.cx(), 1.501D - box.cy(), box.cz());
            rotations[box.index()] = new Quaternionf();
        }
        corpse.acceptPose(0, 0, 0, positions, rotations);
        RecordingBuffers buffers = new RecordingBuffers();
        minecraft.getEntityRenderDispatcher().getRenderer(corpse).render(corpse, 0, 0,
                new PoseStack(), buffers, 0xF000F0);
        return buffers;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    static final class RecordingBuffers implements MultiBufferSource {
        private final Map<String, Counter> counts = new HashMap<>();

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return this.counts.computeIfAbsent(type.toString(), key -> new Counter());
        }

        int vertexCount() {
            return this.counts.values().stream().mapToInt(counter -> counter.vertices).sum();
        }

        boolean has(String material) {
            return this.counts.entrySet().stream()
                    .anyMatch(entry -> entry.getKey().contains(material) && entry.getValue().vertices > 0);
        }
    }

    private static final class Counter implements VertexConsumer {
        private int vertices;

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            require(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z), "出现无效渲染坐标");
            return this;
        }

        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() { this.vertices++; }
        @Override public void defaultColor(int r, int g, int b, int a) { }
        @Override public void unsetDefaultColor() { }
    }
}
