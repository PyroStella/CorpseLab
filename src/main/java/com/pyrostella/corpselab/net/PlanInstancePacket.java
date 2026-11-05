package com.pyrostella.corpselab.net;

import java.util.function.Supplier;

import com.pyrostella.corpselab.plan.BodyPlan;
import com.pyrostella.corpselab.plan.BodyPlanCodec;
import com.pyrostella.corpselab.plan.PlanRegistry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.network.NetworkEvent;

/** 客户端 → 服务端：上报某个实体当前实际渲染尺寸对应的身体计划 */
public final class PlanInstancePacket {

    private final int entityId;
    private final BodyPlan plan;

    public PlanInstancePacket(int entityId, BodyPlan plan) {
        this.entityId = entityId;
        this.plan = plan;
    }

    public PlanInstancePacket(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
        this.plan = BodyPlanCodec.read(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        BodyPlanCodec.write(buf, this.plan);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        ServerPlayer sender = context.get().getSender();
        context.get().enqueueWork(() -> {
            if (sender == null) {
                return;
            }
            Entity target = sender.serverLevel().getEntity(this.entityId);
            if (target instanceof LivingEntity living
                    && sender.distanceToSqr(target) <= 256.0D * 256.0D) {
                PlanRegistry.acceptInstance(living, this.plan);
            }
        });
        context.get().setPacketHandled(true);
    }
}
