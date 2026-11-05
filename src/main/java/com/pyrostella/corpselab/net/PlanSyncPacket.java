package com.pyrostella.corpselab.net;

import java.util.function.Supplier;

import com.pyrostella.corpselab.client.PlanCorpseClientHandler;
import com.pyrostella.corpselab.plan.BodyPlan;
import com.pyrostella.corpselab.plan.BodyPlanCodec;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** 服务端 → 客户端：新跟踪到一具尸体时，把它的骨架发过去 */
public final class PlanSyncPacket {

    private final int entityId;
    private final ResourceLocation sourceType;
    private final BodyPlan plan;

    public PlanSyncPacket(int entityId, ResourceLocation sourceType, BodyPlan plan) {
        this.entityId = entityId;
        this.sourceType = sourceType;
        this.plan = plan;
    }

    public PlanSyncPacket(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
        this.sourceType = buf.readResourceLocation();
        this.plan = BodyPlanCodec.read(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeResourceLocation(this.sourceType);
        BodyPlanCodec.write(buf, this.plan);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PlanCorpseClientHandler.acceptPlan(this.entityId, this.sourceType, this.plan)));
        context.get().setPacketHandled(true);
    }
}
