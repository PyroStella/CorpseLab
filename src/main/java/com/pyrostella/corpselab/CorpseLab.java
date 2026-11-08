package com.pyrostella.corpselab;

import com.pyrostella.corpselab.internal.registry.CorpseLabRegistry;

import com.mojang.logging.LogUtils;
import com.pyrostella.corpselab.internal.config.CorpseLabConfig;
import com.pyrostella.corpselab.internal.network.CorpseLabNetwork;
import com.pyrostella.corpselab.internal.registry.CorpseLabBlock;
import com.pyrostella.corpselab.internal.registry.CorpseLabItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;

@Mod(CorpseLab.MOD_ID)
public class CorpseLab {

    public static final String MOD_ID = "corpselab";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CorpseLab() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext ctx = ModLoadingContext.get();

        // 配置文件
        ctx.registerConfig(ModConfig.Type.SERVER, CorpseLabConfig.SERVER_SPEC);
        ctx.registerConfig(ModConfig.Type.CLIENT, CorpseLabConfig.CLIENT_SPEC);

        CorpseLabRegistry.ENTITY_TYPES.register(modBus);

        // 素材库图标
        if (!FMLLoader.isProduction()) {
            CorpseLabItem.registerCreativeTab();
        }
        CorpseLabItem.ITEMS.register(modBus);
        CorpseLabBlock.BLOCKS.register(modBus);

        CorpseLabNetwork.register();

        LOGGER.info("CorpseLab loaded");
    }
}
