package com.pyrostella.corpselab.net;

import java.util.function.Supplier;

import com.pyrostella.corpselab.plan.BodyPlan;
import com.pyrostella.corpselab.plan.BodyPlanCodec;
import com.pyrostella.corpselab.plan.PlanRegistry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

/**
 * 客户端 → 服务端：上报某个生物类型的骨架。
 *
 * <p>服务端看不到模型，所以骨架只能由客户端算。收到后按类型缓存，第一次为准
 * （同一种生物的模型结构是确定的，后来的重复上报直接丢弃）。</p>
 */
public final class PlanSubmitPacket {

    private final ResourceLocation entityType;
    private final BodyPlan plan;

    public PlanSubmitPacket(ResourceLocation entityType, BodyPlan plan) {
        this.entityType = entityType;
        this.plan = plan;
    }

    public PlanSubmitPacket(FriendlyByteBuf buf) {
        this.entityType = buf.readResourceLocation();
        this.plan = BodyPlanCodec.read(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(this.entityType);
        BodyPlanCodec.write(buf, this.plan);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> PlanRegistry.accept(this.entityType, this.plan));
        context.get().setPacketHandled(true);
    }
}
