package com.pyrostella.corpselab.gametest;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.CorpseLabRegistry;
import com.pyrostella.corpselab.entity.PlanCorpseEntity;
import com.pyrostella.corpselab.net.PlanSyncPacket;
import com.pyrostella.corpselab.plan.BodyPlan;
import com.pyrostella.corpselab.plan.CorpseAppearance;
import com.pyrostella.corpselab.plan.BodyPlanCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** 外观捕获不应依赖具体生物的字段列表 */
@GameTestHolder(CorpseLab.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CorpseAppearanceTests {
    private static BodyPlan plan() {
        return new BodyPlan(List.of(new BodyPlan.Box(0, "body", 0, 1, 0,
                0.25, 0.25, 0.25, 1, 0, 1, 0)), List.of());
    }

    @GameTest(templateNamespace = "forge", template = "empty")
    public static void villagerAppearanceSurvivesSave(GameTestHelper helper) {
        Villager source = EntityType.VILLAGER.create(helper.getLevel());
        source.setVillagerData(new VillagerData(VillagerType.SAVANNA, VillagerProfession.FARMER, 5));
        PlanCorpseEntity corpse = PlanCorpseEntity.spawn(helper.getLevel(), plan(), EntityType.getKey(source.getType()),
                helper.absoluteVec(new Vec3(2, 3, 2)), 0, Vec3.ZERO, source);
        CompoundTag saved = corpse.saveWithoutId(new CompoundTag());
        PlanCorpseEntity restored = CorpseLabRegistry.PLAN_CORPSE.get().create(helper.getLevel());
        restored.load(saved);
        Villager probe = EntityType.VILLAGER.create(helper.getLevel());
        CorpseAppearance.apply(probe, restored.appearance());
        helper.assertTrue(probe.getVillagerData().getType() == VillagerType.SAVANNA
                && probe.getVillagerData().getProfession() == VillagerProfession.FARMER
                && probe.getVillagerData().getLevel() == 5, "村民地区、职业和等级必须跨存档保留");
        CompoundTag copy = corpse.appearance();
        copy.remove("VillagerData");
        helper.assertTrue(corpse.appearance().contains("VillagerData"), "外部不能修改尸体内部外观快照");
        corpse.discard();
        restored.discard();
        helper.succeed();
    }

    @GameTest(templateNamespace = "forge", template = "empty")
    public static void sheepAndEquipmentUseSameSnapshotPath(GameTestHelper helper) {
        Sheep source = EntityType.SHEEP.create(helper.getLevel());
        source.setColor(DyeColor.BLUE);
        source.setSheared(true);
        Sheep probe = EntityType.SHEEP.create(helper.getLevel());
        CorpseAppearance.apply(probe, CorpseAppearance.capture(source));
        helper.assertTrue(probe.getColor() == DyeColor.BLUE && probe.isSheared(), "羊毛颜色与剪毛状态必须保留");
        Zombie equipped = EntityType.ZOMBIE.create(helper.getLevel());
        equipped.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        Zombie equipmentProbe = EntityType.ZOMBIE.create(helper.getLevel());
        CorpseAppearance.apply(equipmentProbe, CorpseAppearance.capture(equipped));
        helper.assertTrue(equipmentProbe.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                "装备层必须能读取死亡时的装备");
        helper.assertTrue(!probe.isAddedToWorld() && !equipmentProbe.isAddedToWorld(), "渲染探针不能进入真实世界");
        helper.succeed();
    }

    @GameTest(templateNamespace = "forge", template = "empty")
    public static void appearancePacketRoundTrip(GameTestHelper helper) {
        Villager source = EntityType.VILLAGER.create(helper.getLevel());
        source.setVillagerData(new VillagerData(VillagerType.SNOW, VillagerProfession.LIBRARIAN, 3));
        FriendlyByteBuf first = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf second = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CompoundTag expected = CorpseAppearance.capture(source);
            PlanSyncPacket packet = new PlanSyncPacket(42, EntityType.getKey(source.getType()), plan(), expected);
            packet.encode(first);
            PlanSyncPacket decoded = new PlanSyncPacket(first);
            decoded.encode(second);
            // CompoundTag 的键没有顺序语义，验证解码后的内容，而不是字节排列
            helper.assertTrue(second.readVarInt() == 42 && second.readResourceLocation().equals(EntityType.getKey(source.getType()))
                    && BodyPlanCodec.read(second).equals(plan()) && expected.equals(second.readNbt())
                    && !second.isReadable(), "网络往返后骨架与外观快照必须保持一致");
        } finally {
            first.release();
            second.release();
        }
        helper.succeed();
    }
}
