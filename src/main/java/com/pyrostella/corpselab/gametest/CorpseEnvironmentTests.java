package com.pyrostella.corpselab.gametest;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.entity.CorpseLimbEntity;
import com.pyrostella.corpselab.entity.PlanCorpseEntity;
import com.pyrostella.corpselab.physics.RigidBody;
import com.pyrostella.corpselab.plan.BodyPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** 在真实服务端世界验证环境入口，纯求解器测试无法覆盖原版流体与爆炸 */
@GameTestHolder(CorpseLab.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CorpseEnvironmentTests {
    private static PlanCorpseEntity corpse(GameTestHelper helper) {
        BodyPlan plan = new BodyPlan(List.of(new BodyPlan.Box(0, "body",
                0, 1.501D, 0, 0.25D, 0.25D, 0.25D, 1, 0, 1.501D, 0)), List.of());
        PlanCorpseEntity corpse = PlanCorpseEntity.spawn(helper.getLevel(), plan,
                new ResourceLocation("minecraft", "pig"), helper.absoluteVec(new Vec3(2, 3, 2)),
                0, Vec3.ZERO, null);
        corpse.tick();
        return corpse;
    }

    private static CorpseLimbEntity limb(GameTestHelper helper, PlanCorpseEntity corpse) {
        List<CorpseLimbEntity> limbs = helper.getLevel().getEntitiesOfClass(CorpseLimbEntity.class,
                corpse.getBoundingBox().inflate(1));
        helper.assertTrue(limbs.size() == 1, "一块骨架应生成一个交互实体");
        return limbs.get(0);
    }

    @GameTest(templateNamespace = "forge", template = "empty")
    public static void externalVelocityAndCleanup(GameTestHelper helper) {
        PlanCorpseEntity corpse = corpse(helper);
        CorpseLimbEntity limb = limb(helper, corpse);
        limb.addDeltaMovement(new Vec3(0.25D, 0.5D, 0));
        helper.assertTrue(limb.consumeMotion(), "实体速度变化应进入刚体");
        RigidBody body = corpse.ragdoll().body(0);
        helper.assertTrue(Math.abs(body.linVel.x - 5.0D) < 1.0E-8D
                && Math.abs(body.linVel.y - 10.0D) < 1.0E-8D, "原版速度与物理速度应正确换算");
        limb.publishPose();
        helper.assertTrue(!limb.consumeMotion(), "回写物理速度不应被再次当成外部施力");
        corpse.discard();
        helper.assertTrue(limb.isRemoved(), "删除尸体必须删除交互实体");
        helper.succeed();
    }

    @GameTest(templateNamespace = "forge", template = "empty")
    public static void waterSlowsLimb(GameTestHelper helper) {
        PlanCorpseEntity corpse = corpse(helper);
        CorpseLimbEntity limb = limb(helper, corpse);
        BlockPos centre = limb.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-1, -1, -1), centre.offset(1, 1, 1))) {
            helper.getLevel().setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        }
        corpse.ragdoll().body(0).linVel.set(10, 0, 0);
        limb.publishPose();
        limb.tick();
        helper.assertTrue(limb.isInWater(), "交互实体必须检测到水");
        helper.assertTrue(limb.consumeMotion(), "水中减速必须进入求解器");
        double speed = corpse.ragdoll().body(0).linVel.x;
        helper.assertTrue(speed > 0 && speed < 10, "水中速度应衰减");
        corpse.discard();
        helper.succeed();
    }

    @GameTest(templateNamespace = "forge", template = "empty")
    public static void explosionLaunchesLimb(GameTestHelper helper) {
        PlanCorpseEntity corpse = corpse(helper);
        CorpseLimbEntity limb = limb(helper, corpse);
        helper.getLevel().explode(null, limb.getX() - 1, limb.getY(), limb.getZ(),
                2.0F, Level.ExplosionInteraction.NONE);
        helper.assertTrue(limb.consumeMotion(), "原版爆炸必须对肢体施力");
        helper.assertTrue(corpse.ragdoll().body(0).linVel.x > 0, "肢体应沿远离爆心的方向受力");
        helper.assertTrue(!corpse.isRemoved(), "爆炸不应删除尸体");
        corpse.discard();
        helper.succeed();
    }

    @GameTest(templateNamespace = "forge", template = "empty")
    public static void flowingWaterPushesLimb(GameTestHelper helper) {
        PlanCorpseEntity corpse = corpse(helper);
        CorpseLimbEntity limb = limb(helper, corpse);
        BlockPos centre = limb.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-1, -1, -1), centre.offset(1, 1, 1))) {
            helper.getLevel().setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        }
        // 东侧水位更低，原版流体应产生朝东的推力
        helper.getLevel().setBlockAndUpdate(centre.east(),
                Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 7));
        limb.tick();
        helper.assertTrue(limb.consumeMotion(), "水流应通过实体速度入口传入求解器");
        helper.assertTrue(corpse.ragdoll().body(0).linVel.x > 0, "肢体应被水流推向较低水位");
        corpse.discard();
        helper.succeed();
    }
}
