package com.pyrostella.corpselab;

import com.mojang.logging.LogUtils;
import com.pyrostella.corpselab.config.CorpseLabConfig;
import com.pyrostella.corpselab.net.CorpseLabNetwork;
import com.pyrostella.corpselab.register.CorpseLabBlock;
import com.pyrostella.corpselab.register.CorpseLabItem;
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

        // 两份 spec 都必须注册，漏掉哪一份，那份里的配置就永远处于未加载状态，第一次读取时每个 tick 都会抛出 "Cannot get config value
        // 服务器设置与通用骨架尸体系统的设置同存一份文件，客户端设置单独一份
        ctx.registerConfig(ModConfig.Type.SERVER, CorpseLabConfig.SERVER_SPEC);
        ctx.registerConfig(ModConfig.Type.CLIENT, CorpseLabConfig.CLIENT_SPEC);

        CorpseLabRegistry.ENTITY_TYPES.register(modBus);

        // 素材库图标：先物品（建 ITEMS_LIST），再方块（往 ITEMS_LIST 追加 BlockItem），顺序不能反过来
        // 标签页只是开发期预览素材用的入口，打包进正式游戏后不注册；物品照常注册，/give 仍然能拿到
        if (!FMLLoader.isProduction()) {
            CorpseLabItem.CREATIVE_MODE_TABS.register(modBus);
        }
        CorpseLabItem.ITEMS.register(modBus);
        CorpseLabBlock.BLOCKS.register(modBus);

        CorpseLabNetwork.register();

        LOGGER.info("CorpseLab loaded");
    }
}
