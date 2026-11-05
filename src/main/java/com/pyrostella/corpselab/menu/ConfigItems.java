package com.pyrostella.corpselab.menu;

import com.pyrostella.corpselab.config.ConfigRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 构造构成设置菜单的那些物品 */
final class ConfigItems {

    /**
     * 分区图标
     *
     * <p>挑选它们是为了让索引页一眼能读明白，而不必去看提示框：TNT 是
     * 触发器，时钟是计时器，骷髅头是尸体
     */
    private static final Map<String, Item> SECTION_ICONS = Map.ofEntries(
            Map.entry("physics", Items.SLIME_BALL),
            Map.entry("grab", Items.FISHING_ROD),
            Map.entry("expiry", Items.CLOCK),
            Map.entry("triggers", Items.TNT),
            Map.entry("corpse", Items.SKELETON_SKULL),
            Map.entry("display", Items.SPYGLASS));

    /** 大致是原版提示框尚未开始挤占屏幕的宽度 */
    private static final int WRAP_WIDTH = 46;

    private ConfigItems() {
    }

    /**
     * 一行 lore 或者名称
     *
     * <p>两者默认都是斜体且颜色古怪，所以每行都自己设样式
     * 而不是去继承
     */
    static Component line(String text, ChatFormatting colour) {
        return Component.literal(text).setStyle(Style.EMPTY.withColor(colour).withItalic(false));
    }

    static ItemStack of(Item item, Component name, List<Component> lore) {
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
        return of(SECTION_ICONS.getOrDefault(name, Items.PAPER),
                line(ConfigRegistry.humanise(name), ChatFormatting.WHITE),
                List.of(line(settings + (settings == 1 ? " setting" : " settings"),
                                ChatFormatting.DARK_GRAY),
                        Component.empty(),
                        line("Click to open", ChatFormatting.GRAY)));
    }

    static ItemStack setting(ConfigRegistry.Entry entry, String value, boolean editable,
                             boolean unavailable) {
        boolean changed = !unavailable && !value.equals(ConfigRegistry.encode(entry.defaultValue()));

        String title = entry.group().isEmpty()
                ? entry.label()
                : ConfigRegistry.humanise(entry.group()) + " · " + entry.label();

        List<Component> lore = new ArrayList<>();
        if (entry.comment() != null) {
            for (String comment : wrap(entry.comment())) {
                lore.add(line(comment, ChatFormatting.GRAY));
            }
        }
        lore.add(Component.empty());

        if (unavailable) {
            lore.add(line("Value unknown - your client has not reported it.",
                    ChatFormatting.RED));
        } else {
            Object decoded = ConfigRegistry.decode(entry, value);
            lore.add(line("Now: ", ChatFormatting.DARK_GRAY).copy().append(
                    line(ConfigRegistry.display(decoded == null ? value : decoded),
                            changed ? ChatFormatting.YELLOW : ChatFormatting.WHITE)));
            lore.add(line("Default: " + ConfigRegistry.display(entry.defaultValue()),
                    ChatFormatting.DARK_GRAY));
        }

        if (entry.min() != null) {
            lore.add(line("Range: " + ConfigRegistry.display(entry.min())
                    + " to " + ConfigRegistry.display(entry.max()), ChatFormatting.DARK_GRAY));
        }

        lore.add(Component.empty());
        if (!editable) {
            lore.add(line("Operators only.", ChatFormatting.RED));
        } else if (entry.kind() == ConfigRegistry.Kind.BOOLEAN) {
            lore.add(line("Click to toggle", ChatFormatting.GRAY));
            lore.add(line("Drop key to reset", ChatFormatting.DARK_GRAY));
        } else if (entry.kind() == ConfigRegistry.Kind.ENUM) {
            lore.add(line("Click to switch to the next one", ChatFormatting.GRAY));
            lore.add(line("Drop key to reset", ChatFormatting.DARK_GRAY));
        } else if (entry.min() != null) {
            lore.add(line("Left click raises · Right click lowers", ChatFormatting.GRAY));
            lore.add(line("Hold shift for ten times the step", ChatFormatting.DARK_GRAY));
            lore.add(line("Drop key to reset", ChatFormatting.DARK_GRAY));
        } else {
            lore.add(line("Change with /corpselab set " + entry.path(), ChatFormatting.DARK_GRAY));
        }

        if (entry.serverSide()) {
            lore.add(line("Applies to everyone", ChatFormatting.DARK_GRAY));
        } else {
            lore.add(line("Applies to you only", ChatFormatting.DARK_GRAY));
        }

        return of(icon(entry, value, unavailable),
                line(title, changed ? ChatFormatting.YELLOW
                        : editable ? ChatFormatting.WHITE : ChatFormatting.GRAY),
                lore);
    }

    /**
     * 把配置注释重新包裹，用于提示框
     *
     * <p>Minecraft 不会对 lore 折行，写多宽就是多宽
     * 而那些注释是按文本文件折的，比提示框想要的宽度宽得多
     * 所以先把它们拼回去，再按可读的宽度重新断开
     */
    private static List<String> wrap(String comment) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentWidth = 0;

        for (String word : comment.replace('\n', ' ').trim().split("\\s+")) {
            // 中文注释整句连写，一个词就超过一整行的宽度，所以先按显示宽度硬切开
            for (String piece : split(word)) {
                int pieceWidth = width(piece);
                if (currentWidth > 0 && currentWidth + 1 + pieceWidth > WRAP_WIDTH) {
                    lines.add(current.toString());
                    current.setLength(0);
                    currentWidth = 0;
                }
                // 中文之间不插空格，只有两边都是西文字符时才补一个
                if (currentWidth > 0 && isAscii(current.charAt(current.length() - 1))
                        && isAscii(piece.charAt(0))) {
                    current.append(' ');
                    currentWidth++;
                }
                current.append(piece);
                currentWidth += pieceWidth;
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    /** 把一个词按显示宽度切成不超过一行宽的若干段 */
    private static List<String> split(String word) {
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        int pieceWidth = 0;

        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            int charWidth = width(c);
            if (pieceWidth > 0 && pieceWidth + charWidth > WRAP_WIDTH) {
                pieces.add(piece.toString());
                piece.setLength(0);
                pieceWidth = 0;
            }
            piece.append(c);
            pieceWidth += charWidth;
        }
        if (piece.length() > 0) {
            pieces.add(piece.toString());
        }
        return pieces;
    }

    /** 一段文字的显示宽度，中文和全角标点按两格算 */
    private static int width(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += width(text.charAt(i));
        }
        return total;
    }

    private static int width(char c) {
        return c < 0x1100 ? 1 : 2;
    }

    private static boolean isAscii(char c) {
        return c < 0x80;
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

    static ItemStack nav(Item item, String name, ChatFormatting colour, String... lore) {
        List<Component> lines = new ArrayList<>();
        for (String entry : lore) {
            lines.add(line(entry, ChatFormatting.DARK_GRAY));
        }
        return of(item, line(name, colour), lines);
    }
}
