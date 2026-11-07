package com.pyrostella.corpselab.command;

import com.mojang.brigadier.context.CommandContext;
import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.api.Corpses;
import com.pyrostella.corpselab.entity.PlanCorpseEntity;
import com.pyrostella.corpselab.menu.ConfigMenu;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/** 设置菜单入口与管理员复活指令 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID)
public final class CorpseLabCommand {
    private CorpseLabCommand() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        // 菜单对所有玩家开放，具体设置的编辑权限由菜单检查
        event.getDispatcher().register(Commands.literal("corpselab")
                .then(Commands.literal("config").executes(CorpseLabCommand::openMenu))
                .then(Commands.literal("revive")
                        .requires(source -> source.hasPermission(ConfigMenu.PERMISSION_LEVEL))
                        .executes(CorpseLabCommand::reviveNearest)
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> revive(context,
                                        EntityArgument.getEntities(context, "targets"))))));
    }

    private static int reviveNearest(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        var corpses = player.serverLevel().getEntitiesOfClass(com.pyrostella.corpselab.entity.PlanCorpseEntity.class,
                player.getBoundingBox().inflate(8));
        var target = corpses.stream().min(java.util.Comparator.comparingDouble(player::distanceToSqr));
        if (target.isEmpty()) { context.getSource().sendFailure(Component.literal("附近没有生物尸体")); return 0; }
        return revive(context,List.of(target.get()));
    }

    private static int revive(CommandContext<CommandSourceStack> context,java.util.Collection<? extends net.minecraft.world.entity.Entity> targets) {
        int started = 0;
        for (var entity : targets) {
            if (!(entity instanceof PlanCorpseEntity corpse)) continue;
            if (Corpses.revive(corpse)) started++;
            else Corpses.revivalProblem(corpse).ifPresent(problem -> context.getSource().sendFailure(
                    Component.translatable("corpselab.revival." + problem.name().toLowerCase(java.util.Locale.ROOT))));
        }
        int count = started;
        if (count > 0) context.getSource().sendSuccess(() -> Component.literal("已开始复活 "+count+" 具生物尸体"),true);
        else if (targets.stream().noneMatch(entity -> entity instanceof PlanCorpseEntity))
            context.getSource().sendFailure(Component.literal("目标不是生物尸体"));
        return started;
    }
    private static int openMenu(CommandContext<CommandSourceStack> context) {
        ServerPlayer player;
        try {
            player = context.getSource().getPlayerOrException();
        } catch (Exception notAPlayer) {
            context.getSource().sendFailure(Component.translatable("corpselab.menu.players_only"));
            return 0;
        }

        ConfigMenu.open(player);
        return 1;
    }

}
