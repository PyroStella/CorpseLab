package com.pyrostella.corpselab.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.pyrostella.corpselab.CorpseLab;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 物理调试视图的开关
 *
 * <pre>
 * /cl 肢体碰撞箱显示          查询当前状态
 * /cl 肢体碰撞箱显示 true     打开
 * /cl 肢体碰撞箱显示 false    关闭
 * </pre>
 *
 * <p>设置名做成字面量节点：Brigadier 匹配字面量时直接比对输入里的原话，所以中文没问题
 * 换成 {@code StringArgumentType.string()} 就不行，它落到 {@code readUnquotedString}
 * 只认 {@code 0-9A-Za-z_-+.}，中文会被吃成空串。</p>
 *
 * <p>注册成纯客户端命令：Forge 的 {@code ClientCommandHandler} 会在
 * {@code ClientPacketListener.sendCommand} 的第一行截住它，不需要服务器知道这条命令
 * 也不吃服务端权限。状态只活在这一局客户端里，不写配置。</p>
 */
@Mod.EventBusSubscriber(modid = CorpseLab.MOD_ID, value = Dist.CLIENT)
public final class CollisionBoxCommand {
    private CollisionBoxCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cl")
                .then(Commands.literal("CollisionBoxDisplay")
                        .executes(context -> report(context.getSource()))
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(CollisionBoxCommand::apply))));
    }

    private static int report(CommandSourceStack source) {
        source.sendSuccess(CollisionBoxCommand::state, false);
        return 1;
    }

    private static int apply(CommandContext<CommandSourceStack> context) {
        CollisionBoxRenderer.setEnabled(BoolArgumentType.getBool(context, "enabled"));
        context.getSource().sendSuccess(CollisionBoxCommand::state, false);
        return 1;
    }

    private static Component state() {
        return Component.literal("CollisionBoxDisplay: " + CollisionBoxRenderer.isEnabled());
    }
}
