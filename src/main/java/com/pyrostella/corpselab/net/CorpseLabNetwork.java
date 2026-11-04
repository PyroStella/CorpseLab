package com.pyrostella.corpselab.net;

import com.pyrostella.corpselab.CorpseLab;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class CorpseLabNetwork {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CorpseLab.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private CorpseLabNetwork() {
    }

    public static void register() {
        int id = 0;

        CHANNEL.messageBuilder(RagdollPosePacket.class, id++)
                .encoder(RagdollPosePacket::encode)
                .decoder(RagdollPosePacket::new)
                .consumerMainThread(RagdollPosePacket::handle)
                .add();

        CHANNEL.messageBuilder(RagdollActionPacket.class, id++)
                .encoder(RagdollActionPacket::encode)
                .decoder(RagdollActionPacket::new)
                .consumerMainThread(RagdollActionPacket::handle)
                .add();

        CHANNEL.messageBuilder(RagdollGrabPacket.class, id++)
                .encoder(RagdollGrabPacket::encode)
                .decoder(RagdollGrabPacket::new)
                .consumerMainThread(RagdollGrabPacket::handle)
                .add();

        CHANNEL.messageBuilder(PlayerMotionPacket.class, id++)
                .encoder(PlayerMotionPacket::encode)
                .decoder(PlayerMotionPacket::new)
                .consumerMainThread(PlayerMotionPacket::handle)
                .add();

        CHANNEL.messageBuilder(RagdollInputPacket.class, id++)
                .encoder(RagdollInputPacket::encode)
                .decoder(RagdollInputPacket::new)
                .consumerMainThread(RagdollInputPacket::handle)
                .add();
        CHANNEL.messageBuilder(ClientSettingsRequestPacket.class, id++)
                .encoder(ClientSettingsRequestPacket::encode)
                .decoder(ClientSettingsRequestPacket::new)
                .consumerMainThread(ClientSettingsRequestPacket::handle)
                .add();

        CHANNEL.messageBuilder(ClientSettingsPacket.class, id++)
                .encoder(ClientSettingsPacket::encode)
                .decoder(ClientSettingsPacket::new)
                .consumerMainThread(ClientSettingsPacket::handle)
                .add();

        CHANNEL.messageBuilder(ClientSettingsApplyPacket.class, id++)
                .encoder(ClientSettingsApplyPacket::encode)
                .decoder(ClientSettingsApplyPacket::new)
                .consumerMainThread(ClientSettingsApplyPacket::handle)
                .add();

        // ---- 通用骨架尸体（新系统）----
        CHANNEL.messageBuilder(PlanSubmitPacket.class, id++)
                .encoder(PlanSubmitPacket::encode)
                .decoder(PlanSubmitPacket::new)
                .consumerMainThread(PlanSubmitPacket::handle)
                .add();

        CHANNEL.messageBuilder(PlanSyncPacket.class, id++)
                .encoder(PlanSyncPacket::encode)
                .decoder(PlanSyncPacket::new)
                .consumerMainThread(PlanSyncPacket::handle)
                .add();

        CHANNEL.messageBuilder(PlanPosePacket.class, id++)
                .encoder(PlanPosePacket::encode)
                .decoder(PlanPosePacket::new)
                .consumerMainThread(PlanPosePacket::handle)
                .add();
    }
}
