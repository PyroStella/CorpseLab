package com.pyrostella.corpselab.internal.network;

import com.pyrostella.corpselab.api.model.BodyPlan;
import com.pyrostella.corpselab.client.sync.PlanCorpseClientHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

/** 服务端向新跟踪者同步完整骨架、外观和可选来源身份 */
public final class PlanSyncPacket {
    private final int entityId;
    private final ResourceLocation sourceType;
    private final BodyPlan plan;
    private final CompoundTag appearance;
    @Nullable
    private final UUID sourceEntityId;

    public PlanSyncPacket(int entityId, ResourceLocation sourceType, BodyPlan plan,
                          CompoundTag appearance, @Nullable UUID sourceEntityId) {
        this.entityId = entityId;
        this.sourceType = sourceType;
        this.plan = plan;
        this.appearance = appearance.copy();
        this.sourceEntityId = sourceEntityId;
    }

    public PlanSyncPacket(FriendlyByteBuf buf) {
        this.entityId = buf.readVarInt();
        this.sourceType = buf.readResourceLocation();
        this.plan = BodyPlanCodec.read(buf);
        // 保留完整外观，避免大型模组附加数据被截断
        CompoundTag tag = buf.readAnySizeNbt();
        this.appearance = tag == null ? new CompoundTag() : tag;
        this.sourceEntityId = buf.readBoolean() ? buf.readUUID() : null;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeResourceLocation(this.sourceType);
        BodyPlanCodec.write(buf, this.plan);
        buf.writeNbt(this.appearance);
        buf.writeBoolean(this.sourceEntityId != null);
        if (this.sourceEntityId != null) buf.writeUUID(this.sourceEntityId);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PlanCorpseClientHandler.acceptPlan(
                        entityId, sourceType, plan, appearance, sourceEntityId));
    }
}
