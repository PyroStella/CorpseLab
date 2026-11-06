package com.pyrostella.corpselab.util;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

/** 按实际载荷验证数量，不为正常模型或配置设置固定条数上限 */
public final class PacketReads {
    private PacketReads() {}

    public static int count(FriendlyByteBuf buf, int minimumEntryBytes) {
        int count = buf.readVarInt();
        if (count < 0 || count > buf.readableBytes() / minimumEntryBytes) {
            throw new DecoderException("数据项数量与剩余载荷不符: " + count);
        }
        return count;
    }
}