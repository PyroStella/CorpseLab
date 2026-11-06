package com.pyrostella.corpselab.menu;

import com.pyrostella.corpselab.config.ConfigRegistry;
import com.pyrostella.corpselab.register.CorpseLabBlock;
import com.pyrostella.corpselab.register.CorpseLabItem;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 菜单物品保留翻译组件，由每位玩家的客户端解析语言 */
final class ConfigItems {
    private static final Map<String, Item> SECTION_ICONS = Map.ofEntries(
            Map.entry("physics", Items.SLIME_BALL),
            Map.entry("grab", Items.FISHING_ROD),
            Map.entry("expiry", Items.CLOCK),
            Map.entry("triggers", Items.TNT),
            Map.entry("corpse", Items.SKELETON_SKULL),
            Map.entry("plan_corpse", Items.BONE),
            Map.entry("display", Items.SPYGLASS));

    private ConfigItems() {
    }

    static Component line(Component text, ChatFormatting colour) {
        return text.copy().setStyle(Style.EMPTY.withColor(colour).withItalic(false));
    }

    static Component line(String key, ChatFormatting colour, Object... args) {
        return line(Component.translatable(key, args), colour);
    }

    static Component sectionName(String section) {
        return Component.translatable("corpselab.config.section." + section);
    }

    static Component settingName(ConfigRegistry.Entry entry) {
        return Component.translatable("corpselab.config." + entry.path());
    }

    private static Component value(ConfigRegistry.Entry entry, Object value) {
        if (value instanceof Boolean flag) {
            return Component.translatable(flag ? "options.on" : "options.off");
        }
        if (value instanceof Enum<?> option) {
            return Component.translatable("corpselab.config." + entry.path()
                    + "." + option.name().toLowerCase(Locale.ROOT));
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return Component.translatable("corpselab.menu.empty");
        }
        return Component.literal(ConfigRegistry.display(value));
    }

    private static ItemStack of(ItemLike item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.setHoverName(name);
        if (!lore.isEmpty()) {
            ListTag tag = new ListTag();
            for (Component entry : lore) {
                tag.add(StringTag.valueOf(Component.Serializer.toJson(entry)));
            }
            stack.getOrCreateTagElement("display").put("Lore", tag);
        }
        return stack;
    }

    static ItemStack section(String name, int settings) {
        return of(sectionIcon(name),
                line(sectionName(name), ChatFormatting.WHITE),
                List.of(line("corpselab.menu.setting_count", ChatFormatting.DARK_GRAY, settings)));
    }

    /** 尸体分区改用模组自己的头骨方块当图标；方块要等注册完成才取得，所以不能放进静态表 */
    private static ItemLike sectionIcon(String name) {
        return switch (name) {
            case "corpse" -> CorpseLabBlock.PLAYER_SKULL.get();
            case "plan_corpse" -> CorpseLabBlock.MONSTER_SKULL.get();
            default -> SECTION_ICONS.getOrDefault(name, Items.PAPER);
        };
    }

    static ItemStack setting(ConfigRegistry.Entry entry, String raw, boolean editable,
                             boolean unavailable, boolean unsaved) {
        boolean changed = !unavailable && !raw.equals(ConfigRegistry.encode(entry.defaultValue()));
        List<Component> lore = new ArrayList<>();
        lore.add(line("corpselab.config." + entry.path() + ".description", ChatFormatting.GRAY));
        if (unavailable) {
            lore.add(line("corpselab.menu.loading", ChatFormatting.GRAY));
        } else {
            Object decoded = ConfigRegistry.decode(entry, raw);
            lore.add(line("corpselab.menu.current", ChatFormatting.GRAY,
                    line(value(entry, decoded == null ? raw : decoded),
                            changed ? ChatFormatting.YELLOW : ChatFormatting.WHITE)));
            if (unsaved) {
                lore.add(line("corpselab.menu.unsaved", ChatFormatting.YELLOW));
            }
        }
        lore.add(line("corpselab.menu.default", ChatFormatting.DARK_GRAY,
                value(entry, entry.defaultValue())));
        if (entry.min() != null) {
            lore.add(line("corpselab.menu.range", ChatFormatting.DARK_GRAY,
                    ConfigRegistry.display(entry.min()), ConfigRegistry.display(entry.max())));
        }

        lore.add(Component.empty());
        if (!editable) {
            lore.add(line("corpselab.menu.operators_only", ChatFormatting.RED));
        } else if (!unavailable) {
            switch (entry.kind()) {
                case BOOLEAN -> lore.add(line("corpselab.menu.toggle", ChatFormatting.GRAY));
                case ENUM -> lore.add(line("corpselab.menu.cycle", ChatFormatting.GRAY));
                case INTEGER, DECIMAL -> lore.add(line("corpselab.menu.adjust", ChatFormatting.GRAY));
                default -> lore.add(line("corpselab.menu.edit_file", ChatFormatting.GRAY));
            }
            lore.add(line("corpselab.menu.reset", ChatFormatting.DARK_GRAY));
        }
        lore.add(line(entry.serverSide() ? "corpselab.menu.scope.world" : "corpselab.menu.scope.client",
                ChatFormatting.DARK_GRAY));

        return of(icon(entry, raw, unavailable),
                line(settingName(entry), changed ? ChatFormatting.YELLOW
                        : editable ? ChatFormatting.WHITE : ChatFormatting.GRAY), lore);
    }

    private static Item icon(ConfigRegistry.Entry entry, String value, boolean unavailable) {
        if (unavailable) {
            return Items.BARRIER;
        }
        return switch (entry.kind()) {
            case BOOLEAN -> Boolean.parseBoolean(value) ? Items.LIME_DYE : Items.GRAY_DYE;
            case INTEGER, DECIMAL -> Items.COMPARATOR;
            case STRING_LIST -> Items.WRITABLE_BOOK;
            case STRING, ENUM -> Items.NAME_TAG;
        };
    }

    static ItemStack nav(Item item, Component name, ChatFormatting colour, Component... lore) {
        return of(item, line(name, colour),
                java.util.Arrays.stream(lore).map(text -> line(text, ChatFormatting.DARK_GRAY)).toList());
    }
}
