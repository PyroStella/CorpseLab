package com.pyrostella.corpselab.internal.registry;

import com.pyrostella.corpselab.CorpseLab;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.google.common.collect.ObjectArrays;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用物品注册。
 * <p>
 * 素材库（{@code 设计文件/通用素材库/界面图标/物品}）里的界面图标全部按普通物品注册，不带任何行为，
 * 只为了方便在游戏里预览与取用。
 * <p>
 * 结构参照防御塔的 {@code EDTItem}：一个 {@code DeferredRegister} + 一个有序列表 + 一个创造模式标签页，
 * 方块对应的 BlockItem 也从这个类注册，保证标签页里的顺序可控。
 */
public class CorpseLabItem {

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, CorpseLab.MOD_ID);

    /** 创造模式标签页的展示顺序＝此列表的添加顺序；方块物品由 {@link CorpseLabBlock} 追加到末尾 */
    public static final List<RegistryObject<Item>> ITEMS_LIST = new ArrayList<>();

    // ==== 界面图标：叉与勾 ====
    public static final RegistryObject<Item> RED_CROSS = registerItem("red_cross");
    public static final RegistryObject<Item> FORBIDDEN = registerItem("forbidden");
    public static final RegistryObject<Item> GREEN_CHECK = registerItem("green_check");

    // ==== 界面图标：红色四向箭头 ====
    public static final RegistryObject<Item> RED_ARROW_UP = registerItem("red_arrow_up");
    public static final RegistryObject<Item> RED_ARROW_DOWN = registerItem("red_arrow_down");
    public static final RegistryObject<Item> RED_ARROW_LEFT = registerItem("red_arrow_left");
    public static final RegistryObject<Item> RED_ARROW_RIGHT = registerItem("red_arrow_right");

    // ==== 界面图标：绿色四向箭头 ====
    public static final RegistryObject<Item> GREEN_ARROW_UP = registerItem("green_arrow_up");
    public static final RegistryObject<Item> GREEN_ARROW_DOWN = registerItem("green_arrow_down");
    public static final RegistryObject<Item> GREEN_ARROW_LEFT = registerItem("green_arrow_left");
    public static final RegistryObject<Item> GREEN_ARROW_RIGHT = registerItem("green_arrow_right");

    /**
     * 注册一个普通物品（无特殊行为）。
     */
    public static RegistryObject<Item> registerItem(String id) {
        RegistryObject<Item> item = ITEMS.register(id, () -> new Item(new Item.Properties()));
        ITEMS_LIST.add(item);
        return item;
    }

    /**
     * 给方块注册对应的 BlockItem。
     * <p>
     * 物品栏里的显示由方块模型继承的 {@code block/block} 决定，即原版方块的立体展示样式，
     * 不走 {@code item/generated} 的平面贴图。
     */
    public static RegistryObject<Item> registerBlockItem(String id, RegistryObject<? extends Block> block) {
        RegistryObject<Item> item = ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties()));
        ITEMS_LIST.add(item);
        return item;
    }

    // ==== 创造模式标签页 ====
    /**
     * 1.19.2 还没有标签页注册表（1.19.3 才有），只能自己实现一个再塞进 {@link CreativeModeTab#TABS}
     * <p>
     * 与原版标签页一样按 {@link #ITEMS_LIST} 的顺序填充，方块物品由 {@link CorpseLabBlock} 追加。
     */
    public static final CreativeModeTab MAIN_TAB = new CreativeModeTab(CorpseLab.MOD_ID) {
        @Override
        public ItemStack makeIcon() {
            return new ItemStack(RED_CROSS.get());
        }

        @Override
        public Component getDisplayName() {
            return Component.translatable("itemGroup.corpselab.icons");
        }

        @Override
        public void fillItemList(NonNullList<ItemStack> items) {
            for (RegistryObject<Item> item : ITEMS_LIST) {
                items.add(new ItemStack(item.get()));
            }
        }
    };

    /** 只在开发环境调用：把标签页挂到原版标签页数组末尾 */
    public static void registerCreativeTab() {
        CreativeModeTab.TABS = ObjectArrays.concat(CreativeModeTab.TABS, MAIN_TAB);
    }
}
