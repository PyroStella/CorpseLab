package com.pyrostella.corpselab.internal.menu;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.internal.corpse.CorpseEntity;
import com.pyrostella.corpselab.internal.registry.CorpseLabItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkHooks;

/** 使用原版六行箱子界面，末尾两个虚拟槽位由服务端处理操作 */
public final class CorpseMenu extends AbstractContainerMenu {
    private static final int DELETE_SLOT = 52;
    private static final int TRANSFER_SLOT = 53;
    private static final int CHEST_SIZE = 54;

    private final CorpseEntity corpse;
    private final Player viewer;
    private final SimpleContainer actions = new SimpleContainer(2);
    private boolean deletionPending;

    public CorpseMenu(int id, Inventory inventory, CorpseEntity corpse) {
        super(MenuType.GENERIC_9x6, id);
        this.corpse = corpse;
        this.viewer = inventory.player;
        for (int slot = 0; slot < CorpseEntity.SLOT_COUNT; slot++) {
            addSlot(new Slot(corpse.contents(), slot, 8 + slot % 9 * 18, 18 + slot / 9 * 18));
        }
        for (int index = 0; index < 2; index++) {
            addSlot(new Slot(actions, index, 134 + index * 18, 108) {
                @Override
                public boolean mayPlace(ItemStack stack) { return false; }
                @Override
                public boolean mayPickup(Player player) { return false; }
            });
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, 139 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, 197));
        }
        updateActions();
    }

    public static void open(Player player, CorpseEntity corpse) {
        if (player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, new SimpleMenuProvider(
                    (id, inventory, opener) -> new CorpseMenu(id, inventory, corpse), corpse.displayTitle()));
        }
    }

    private void updateActions() {
        actions.setItem(0, ConfigItems.nav(CorpseLabItem.RED_CROSS.get(),
                Component.translatable(CorpseLab.MOD_ID + (deletionPending
                        ? ".corpse.menu.delete_confirm" : ".corpse.menu.delete_all")), ChatFormatting.RED));
        actions.setItem(1, ConfigItems.nav(CorpseLabItem.GREEN_CHECK.get(),
                Component.translatable(CorpseLab.MOD_ID + ".corpse.menu.transfer_all"), ChatFormatting.GREEN));
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!stillValid(player) || player.isSpectator()) return;
        if (slotId == DELETE_SLOT || slotId == TRANSFER_SLOT) {
            // 只接受普通点击，禁止 Shift、丢弃、数字键及拖拽触发批量操作
            if (clickType == ClickType.PICKUP && (button == 0 || button == 1)) {
                if (slotId == TRANSFER_SLOT) {
                    deletionPending = false;
                    corpse.equipOnto(player);
                } else if (deletionPending) {
                    deletionPending = false;
                    corpse.deleteAllItems();
                } else {
                    deletionPending = true;
                }
            }
            updateActions();
            // 原版客户端会预测拿取按钮，重发服务端内容以纠正显示
            sendAllDataToRemote();
            return;
        }
        if (deletionPending) {
            deletionPending = false;
            updateActions();
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotId) {
        if (!stillValid(player) || player.isSpectator() || slotId < 0 || slotId >= slots.size()
                || slotId == DELETE_SLOT || slotId == TRANSFER_SLOT) return ItemStack.EMPTY;
        Slot slot = slots.get(slotId);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        // 玩家向尸体移动时不遍历操作槽位，避免物品与按钮图标合并
        if (slotId < CorpseEntity.SLOT_COUNT) {
            if (!moveItemStackTo(stack, CHEST_SIZE, slots.size(), true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 0, CorpseEntity.SLOT_COUNT, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return before;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != actions && super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return slot.container != actions && super.canDragTo(slot);
    }

    @Override
    public boolean stillValid(Player player) {
        return player == viewer && !corpse.isRemoved() && player.level == corpse.level
                && player.distanceToSqr(corpse) <= 64;
    }
}
