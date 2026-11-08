package com.pyrostella.corpselab.internal.menu;

import com.pyrostella.corpselab.internal.corpse.CorpseEntity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraftforge.network.NetworkHooks;

/**
 * 用 Minecraft 自带的箱子界面打开尸体
 *
 * <p>玩家的物品装得进五行，所以没有理由为此单独提供自定义菜单
 * 和界面：原版已经处理了槽位同步、shift 点击以及客户端 GUI
 * 而且这是玩家本来就熟悉的交互方式
 */
public final class CorpseMenu {

    private CorpseMenu() {
    }

    public static void open(Player player, CorpseEntity corpse) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        NetworkHooks.openScreen(serverPlayer, new SimpleMenuProvider(
                (id, inventory, opener) -> ChestMenu.sixRows(id, inventory, corpse.contents()),
                corpse.displayTitle()));
    }
}
