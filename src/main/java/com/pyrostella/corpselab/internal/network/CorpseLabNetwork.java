package com.pyrostella.corpselab.internal.network;

import com.pyrostella.corpselab.CorpseLab;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.simple.SimpleChannel;

public final class CorpseLabNetwork {

    private static final String PROTOCOL = "7";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CorpseLab.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private CorpseLabNetwork() {
    }

    public static void register() {
        int id = 0;

        CHANNEL.messageBuilder(RagdollPosePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RagdollPosePacket::encode)
                .decoder(RagdollPosePacket::new)
                .consumerMainThread(RagdollPosePacket::handle)
                .add();

        CHANNEL.messageBuilder(RagdollGrabPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RagdollGrabPacket::encode)
                .decoder(RagdollGrabPacket::new)
                .consumerMainThread(RagdollGrabPacket::handle)
                .add();

        CHANNEL.messageBuilder(ClientSettingsRequestPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ClientSettingsRequestPacket::encode)
                .decoder(ClientSettingsRequestPacket::new)
                .consumerMainThread(ClientSettingsRequestPacket::handle)
                .add();

        CHANNEL.messageBuilder(ClientSettingsPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ClientSettingsPacket::encode)
                .decoder(ClientSettingsPacket::new)
                .consumerMainThread(ClientSettingsPacket::handle)
                .add();

        CHANNEL.messageBuilder(ClientSettingsApplyPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ClientSettingsApplyPacket::encode)
                .decoder(ClientSettingsApplyPacket::new)
                .consumerMainThread(ClientSettingsApplyPacket::handle)
                .add();

        // 通用骨架尸体
        CHANNEL.messageBuilder(PlanSubmitPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PlanSubmitPacket::encode)
                .decoder(PlanSubmitPacket::new)
                .consumerMainThread(PlanSubmitPacket::handle)
                .add();

        CHANNEL.messageBuilder(PlanInstancePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PlanInstancePacket::encode)
                .decoder(PlanInstancePacket::new)
                .consumerMainThread(PlanInstancePacket::handle)
                .add();

        CHANNEL.messageBuilder(PlanSyncPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PlanSyncPacket::encode)
                .decoder(PlanSyncPacket::new)
                .consumerMainThread(PlanSyncPacket::handle)
                .add();

        CHANNEL.messageBuilder(PlanPosePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PlanPosePacket::encode)
                .decoder(PlanPosePacket::new)
                .consumerMainThread(PlanPosePacket::handle)
                .add();
    }
}
