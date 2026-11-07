package com.pyrostella.corpselab.internal.network;

import java.util.function.Supplier;

import com.pyrostella.corpselab.client.sync.PlanCorpseClientHandler;
import com.pyrostella.corpselab.api.model.BodyPlan;
import com.pyrostella.corpselab.internal.network.BodyPlanCodec;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** 服务端 → 客户端：新跟踪到一具尸体时，把它的骨架发过去 */
public final class PlanSyncPacket {

    private final int entityId;
    private final ResourceLocation sourceType;
    private final BodyPlan plan;
    private final CompoundTag appearance;

    public PlanSyncPacket(int entityId, ResourceLocation sourceType, BodyPlan plan, CompoundTag appearance) {
        this.entityId = entityId;
        this.sourceType = sourceType;
        this.plan = plan;
        this.appearance = appearance.copy();
    }

    public PlanSyncPacket(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
        this.sourceType = buf.readResourceLocation();
        this.plan = BodyPlanCodec.read(buf);
        // 服务端外观已受外层数据包协议约束，保留完整 NBT 快照
        CompoundTag tag = buf.readAnySizeNbt();
        this.appearance = tag == null ? new CompoundTag() : tag;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeResourceLocation(this.sourceType);
        BodyPlanCodec.write(buf, this.plan);
        buf.writeNbt(this.appearance);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PlanCorpseClientHandler.acceptPlan(this.entityId, this.sourceType, this.plan, this.appearance)));
        context.get().setPacketHandled(true);
    }
}
