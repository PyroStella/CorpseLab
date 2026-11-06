package com.pyrostella.corpselab.client.gametest;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.client.model.CorpseModelAdapters;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** 独立测试源码集中的可选依赖及几何回归检查 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GeckoCompatibilityTests {
    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        if (!Boolean.getBoolean("corpselab.renderTest")) return;
        event.enqueueWork(() -> {
            // 未安装 GeckoLib 时也必须能初始化整个适配器注册表
            CorpseModelAdapters.version();
            if (ModList.get().isLoaded("geckolib")) Geometry.check();
            CorpseLab.LOGGER.info("GeckoLib 可选兼容启动自检通过，已安装={}", ModList.get().isLoaded("geckolib"));
        });
    }

    private static final class Geometry {
        static void check() {
            require(java.util.Arrays.stream(software.bernie.geckolib.renderer.GeoEntityRenderer.class.getDeclaredMethods())
                    .anyMatch(method -> method.getName().equals("renderCube")), "GeckoLib 材质层兼容注入未生效");
            var root = new software.bernie.geckolib.cache.object.GeoBone(null, "root", false, 0d, false, false);
            var child = new software.bernie.geckolib.cache.object.GeoBone(root, "body", false, 0d, false, false);
            root.getChildBones().add(child);
            child.setPivotY(16);
            child.setRotZ((float)Math.PI/2);
            child.saveInitialSnapshot();
            root.saveInitialSnapshot();
            var vertices = new software.bernie.geckolib.cache.object.GeoVertex[] {
                    new software.bernie.geckolib.cache.object.GeoVertex(0,1,0),
                    new software.bernie.geckolib.cache.object.GeoVertex(1,1,0),
                    new software.bernie.geckolib.cache.object.GeoVertex(1,3,1),
                    new software.bernie.geckolib.cache.object.GeoVertex(0,3,1)
            };
            var quad = new software.bernie.geckolib.cache.object.GeoQuad(vertices, new org.joml.Vector3f(0,0,1),
                    net.minecraft.core.Direction.SOUTH);
            child.getCubes().add(new software.bernie.geckolib.cache.object.GeoCube(
                    new software.bernie.geckolib.cache.object.GeoQuad[]{quad}, net.minecraft.world.phys.Vec3.ZERO,
                    net.minecraft.world.phys.Vec3.ZERO, new net.minecraft.world.phys.Vec3(16,32,16), 0, false));
            var model = new software.bernie.geckolib.cache.object.BakedGeoModel(java.util.List.of(root), null);
            var scan = com.pyrostella.corpselab.client.model.gecko.GeckoModelScan.scan(model, new org.joml.Matrix4f());
            require(scan.plan().boxCount()==2 && scan.plan().jointCount()==1, "空父骨骼必须保留连接");
            var body = scan.plan().boxes().get(1);
            require(body.path().equals("root.body") && scan.plan().joints().get(0).parent()==0, "必须使用真实骨骼路径和父子关系");
            near(body.cx(),1); near(body.cy(),0.001); near(body.hx(),1); near(body.hy(),0.5); near(body.hz(),0.5);
            // 活体随后改变共享骨骼动画，静态扫描不能被污染，也不能重置活体状态
            child.setRotZ(0.7f);
            var again = com.pyrostella.corpselab.client.model.gecko.GeckoModelScan.scan(model, new org.joml.Matrix4f());
            require(again.plan().equals(scan.plan()), "动画不能污染初始骨架");
            near(child.getRotZ(),0.7);
            var scaled = com.pyrostella.corpselab.client.model.gecko.GeckoModelScan.scan(model, new org.joml.Matrix4f().scaling(2,3,4));
            near(scaled.plan().boxes().get(1).hx(),2); near(scaled.plan().boxes().get(1).hy(),1.5);
            near(scaled.plan().boxes().get(1).hz(),2);
        }
        static void near(double actual, double expected) {
            require(Math.abs(actual-expected)<1e-5, "几何变换错误: "+actual+" != "+expected);
        }
        static void require(boolean ok, String message) {
            if (!ok) throw new IllegalStateException(message);
        }
    }
}