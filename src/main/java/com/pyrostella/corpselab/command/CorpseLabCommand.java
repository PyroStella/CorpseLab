package com.pyrostella.corpselab.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.config.ConfigRegistry;
import com.pyrostella.corpselab.menu.ConfigMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端侧控制模组拥有的每一项设置
 *
 * <p>门槛设在权限等级 2，与原版给游戏规则以及其他改变世界行为方式的
 * 命令所设的门槛一致：在服务器上只有管理员能碰到其中任何一项
 * 在单人游戏里则要开启作弊之后才可用
 *
 * <p>每个选项都从 {@link ConfigRegistry} 发现，所以补全和
 * 值解析都会自动跟随配置，而不必在这里再复述一遍
 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID)
public final class CorpseLabCommand {

    /** 与原版 /gamerule 要求的等级相同 */
    private static final int PERMISSION_LEVEL = 2;

    /**
     * 从这里只能碰到服务端设置
     *
     * <p>客户端设置属于每个玩家自己的游戏，而在专用服务端上
     * 客户端文件根本不会被加载，读它就会抛异常
     */
    private static List<ConfigRegistry.Entry> settings() {
        return ConfigRegistry.entries().stream().filter(ConfigRegistry.Entry::serverSide).toList();
    }

    private static final SuggestionProvider<CommandSourceStack> SETTINGS =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    settings().stream().map(ConfigRegistry.Entry::path).toList(), builder);

    private CorpseLabCommand() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 菜单本身对所有人开放：这是玩家在游戏里碰到自己显示设置的
        // 唯一途径，而它对没有权限等级的人拒绝一切服务端设置
        // 用命令编辑则仅限管理员
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("corpselab");

        root.then(Commands.literal("config")
                .executes(CorpseLabCommand::openMenu));

        root.then(Commands.literal("list")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .executes(context -> list(context, null))
                .then(Commands.argument("section", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                sections().keySet(), builder))
                        .executes(context -> list(context, StringArgumentType.getString(context, "section")))));

        root.then(Commands.literal("get")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.argument("setting", StringArgumentType.string())
                        .suggests(SETTINGS)
                        .executes(CorpseLabCommand::get)));

        root.then(Commands.literal("set")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.argument("setting", StringArgumentType.string())
                        .suggests(SETTINGS)
                        // 数字和布尔各自成为一条分支，这样 Brigadier 能
                        // 分别正确校验和补全，而不是当成字符串收下
                        .then(Commands.argument("number", DoubleArgumentType.doubleArg())
                                .executes(context -> set(context, DoubleArgumentType.getDouble(context, "number"))))
                        .then(Commands.argument("flag", BoolArgumentType.bool())
                                .executes(context -> set(context, BoolArgumentType.getBool(context, "flag"))))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> set(context, StringArgumentType.getString(context, "text"))))));

        root.then(Commands.literal("reset")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.literal("all").executes(CorpseLabCommand::resetAll))
                .then(Commands.argument("setting", StringArgumentType.string())
                        .suggests(SETTINGS)
                        .executes(CorpseLabCommand::reset)));

        dispatcher.register(root);
    }

    private static int openMenu(CommandContext<CommandSourceStack> context) {
        ServerPlayer player;
        try {
            player = context.getSource().getPlayerOrException();
        } catch (Exception notAPlayer) {
            context.getSource().sendFailure(Component.literal("Only a player can open the settings menu."));
            return 0;
        }

        ConfigMenu.open(player);
        return 1;
    }

    /** 至少含一项服务端设置的分区，保持声明顺序 */
    private static Map<String, List<ConfigRegistry.Entry>> sections() {
        Map<String, List<ConfigRegistry.Entry>> grouped = new LinkedHashMap<>();
        for (ConfigRegistry.Entry entry : settings()) {
            grouped.computeIfAbsent(entry.section(), key -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    private static int list(CommandContext<CommandSourceStack> context, String section) {
        Map<String, List<ConfigRegistry.Entry>> grouped = sections();

        if (section != null && !grouped.containsKey(section)) {
            context.getSource().sendFailure(Component.literal("No section called " + section + "."));
            return 0;
        }

        grouped.forEach((name, entries) -> {
            if (section != null && !section.equals(name)) {
                return;
            }
            context.getSource().sendSuccess(() ->
                    Component.literal(name).withStyle(ChatFormatting.GOLD), false);

            for (ConfigRegistry.Entry entry : entries) {
                Object value = ConfigRegistry.get(entry);
                boolean changed = !String.valueOf(value).equals(String.valueOf(entry.defaultValue()));

                context.getSource().sendSuccess(() -> Component.literal("  " + entry.path() + " = ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(ConfigRegistry.display(value))
                                .withStyle(changed ? ChatFormatting.YELLOW : ChatFormatting.WHITE)), false);
            }
        });
        return 1;
    }

    private static int get(CommandContext<CommandSourceStack> context) {
        ConfigRegistry.Entry entry = require(context);
        if (entry == null) {
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.literal(entry.path() + " = ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(ConfigRegistry.display(ConfigRegistry.get(entry)))
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  (default " + ConfigRegistry.display(entry.defaultValue()) + ")")
                        .withStyle(ChatFormatting.DARK_GRAY)), false);

        if (entry.comment() != null) {
            for (String line : entry.comment().split("\n")) {
                context.getSource().sendSuccess(() ->
                        Component.literal("  " + line).withStyle(ChatFormatting.DARK_GRAY), false);
            }
        }
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> context, Object raw) {
        ConfigRegistry.Entry entry = require(context);
        if (entry == null) {
            return 0;
        }

        Object value = coerce(entry, raw);
        if (value == null) {
            String expects = entry.kind() == ConfigRegistry.Kind.ENUM
                    ? "one of " + String.join(", ", ConfigRegistry.options(entry))
                    : entry.kind().name().toLowerCase();
            context.getSource().sendFailure(Component.literal(entry.path() + " expects " + expects + "."));
            return 0;
        }

        String error = ConfigRegistry.set(entry, value);
        if (error != null) {
            context.getSource().sendFailure(Component.literal(entry.path() + ": " + error));
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.literal("Set ")
                .append(Component.literal(entry.path()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" to " + ConfigRegistry.display(value))
                        .withStyle(ChatFormatting.YELLOW)), true);
        return 1;
    }

    /** 把松散类型的命令参数转成设置真正持有的类型 */
    private static Object coerce(ConfigRegistry.Entry entry, Object raw) {
        return switch (entry.kind()) {
            case BOOLEAN -> raw instanceof Boolean flag ? flag
                    : raw instanceof String text ? Boolean.parseBoolean(text) : null;
            case INTEGER -> raw instanceof Number number ? (int) Math.round(number.doubleValue()) : null;
            case DECIMAL -> raw instanceof Number number ? number.doubleValue() : null;
            case STRING_LIST -> raw instanceof String text
                    ? List.of(text.split("\\s*,\\s*"))
                    : null;
            case STRING -> String.valueOf(raw);
            case ENUM -> ConfigRegistry.parseEnum(entry, String.valueOf(raw));
        };
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        ConfigRegistry.Entry entry = require(context);
        if (entry == null) {
            return 0;
        }

        String error = ConfigRegistry.reset(entry);
        if (error != null) {
            context.getSource().sendFailure(Component.literal(entry.path() + ": " + error));
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.literal("Reset " + entry.path()
                + " to " + ConfigRegistry.display(entry.defaultValue())), true);
        return 1;
    }

    private static int resetAll(CommandContext<CommandSourceStack> context) {
        int count = 0;
        for (ConfigRegistry.Entry entry : ConfigRegistry.entries()) {
            if (entry.serverSide() && ConfigRegistry.reset(entry) == null) {
                count++;
            }
        }

        int total = count;
        context.getSource().sendSuccess(() ->
                Component.literal("Reset " + total + " settings to their defaults."), true);
        return 1;
    }

    private static ConfigRegistry.Entry require(CommandContext<CommandSourceStack> context) {
        String path = StringArgumentType.getString(context, "setting");
        ConfigRegistry.Entry entry = ConfigRegistry.find(path);
        if (entry == null || !entry.serverSide()) {
            context.getSource().sendFailure(Component.literal("No setting called " + path + "."));
            return null;
        }
        return entry;
    }
}
