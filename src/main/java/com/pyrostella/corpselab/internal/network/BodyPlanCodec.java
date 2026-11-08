package com.pyrostella.corpselab.internal.network;

import com.pyrostella.corpselab.api.model.BodyPlan;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import com.pyrostella.corpselab.internal.network.PacketReads;
import io.netty.handler.codec.DecoderException;

/**
 * {@link BodyPlan} 的网络编解码
 *
 * <p>全量传输完整模型，通过载荷长度和骨架结构检查保证格式一致。</p>
 *
 * <p>带格式号：盒子多了枢轴三点，旧格式（没有枢轴）读进来会错位
 * 所以开头写一个 magic，读到不认识的就抛异常，让调用方自己处理。</p>
 */
public final class BodyPlanCodec {

    /** "PLP2"：第二版格式，盒子带枢轴 */
    private static final int MAGIC = 0x504C5032;

    private BodyPlanCodec() {
    }

    public static void write(FriendlyByteBuf buf, BodyPlan plan) {
        if (!plan.isValid()) throw new IllegalArgumentException("骨架数据无效");
        buf.writeInt(MAGIC);

        buf.writeVarInt(plan.boxes().size());
        for (BodyPlan.Box box : plan.boxes()) {
            buf.writeUtf(box.path());
            buf.writeFloat((float) box.cx());
            buf.writeFloat((float) box.cy());
            buf.writeFloat((float) box.cz());
            buf.writeFloat((float) box.hx());
            buf.writeFloat((float) box.hy());
            buf.writeFloat((float) box.hz());
            buf.writeFloat((float) box.mass());
            buf.writeFloat((float) box.px());
            buf.writeFloat((float) box.py());
            buf.writeFloat((float) box.pz());
        }

        buf.writeVarInt(plan.joints().size());
        for (BodyPlan.Joint joint : plan.joints()) {
            buf.writeVarInt(joint.child());
            buf.writeVarInt(joint.parent());
        }
    }

    public static BodyPlan read(FriendlyByteBuf buf) {
        int magic = buf.readInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("未知的骨架格式 0x" + Integer.toHexString(magic));
        }

        int boxCount = PacketReads.count(buf, 41);
        List<BodyPlan.Box> boxes = new ArrayList<>(boxCount);
        for (int i = 0; i < boxCount; i++) {
            // 与写入端使用相同的 Minecraft 默认字符串格式，允许完整模型层级路径
            String path = buf.readUtf();
            boxes.add(new BodyPlan.Box(i, path,
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat()));
        }

        int jointCount = PacketReads.count(buf, 2);
        List<BodyPlan.Joint> joints = new ArrayList<>(jointCount);
        for (int i = 0; i < jointCount; i++) {
            joints.add(new BodyPlan.Joint(buf.readVarInt(), buf.readVarInt()));
        }

        BodyPlan plan = new BodyPlan(List.copyOf(boxes), List.copyOf(joints));
        if (!plan.isValid()) throw new DecoderException("骨架数据无效");
        return plan;
    }
}
