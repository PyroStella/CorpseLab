package com.pyrostella.corpselab;

import com.mojang.logging.LogUtils;
import com.pyrostella.corpselab.config.CorpseLabConfig;
import com.pyrostella.corpselab.net.CorpseLabNetwork;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(CorpseLab.MOD_ID)
public class CorpseLab {

    public static final String MOD_ID = "corpselab";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CorpseLab() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext ctx = ModLoadingContext.get();

        // Both specs must be registered. Registering only some of them leaves the others
        // permanently unloaded, and the first read throws "Cannot get config value before
        // config is loaded" on every tick. See MEMORY.md.
        ctx.registerConfig(ModConfig.Type.SERVER, CorpseLabConfig.SERVER_SPEC);
        ctx.registerConfig(ModConfig.Type.CLIENT, CorpseLabConfig.CLIENT_SPEC);
        // 通用骨架尸体系统自己的配置（形态：铰接 / 自由）
        // 必须指定独立文件名：Forge 默认按 <modid>-<type>.toml 命名，会和上面那份 SERVER 配置撞名
        ctx.registerConfig(ModConfig.Type.SERVER, com.pyrostella.corpselab.plan.PlanConfig.SPEC,
                "corpselab-plan-server.toml");

        CorpseLabRegistry.ENTITY_TYPES.register(modBus);
        CorpseLabNetwork.register();

        LOGGER.info("CorpseLab loaded");
    }
}
