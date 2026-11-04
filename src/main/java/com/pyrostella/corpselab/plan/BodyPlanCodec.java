package com.pyrostella.corpselab.plan;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;

/**
 * {@link BodyPlan} 的网络编解码。
 *
 * <p>计划很小（十几个盒子），直接全量发，不做增量。</p>
 */
public final class BodyPlanCodec {

    private BodyPlanCodec() {
    }

    public static void write(FriendlyByteBuf buf, BodyPlan plan) {
        buf.writeVarInt(plan.boxes().size());
        for (BodyPlan.Box box : plan.boxes()) {
            buf.writeUtf(box.path(), 96);
            buf.writeFloat((float) box.cx());
            buf.writeFloat((float) box.cy());
            buf.writeFloat((float) box.cz());
            buf.writeFloat((float) box.hx());
            buf.writeFloat((float) box.hy());
            buf.writeFloat((float) box.hz());
            buf.writeFloat((float) box.mass());
        }

        buf.writeVarInt(plan.joints().size());
        for (BodyPlan.Joint joint : plan.joints()) {
            buf.writeVarInt(joint.child());
            buf.writeVarInt(joint.parent());
        }
    }

    public static BodyPlan read(FriendlyByteBuf buf) {
        int boxCount = buf.readVarInt();
        List<BodyPlan.Box> boxes = new ArrayList<>(boxCount);
        for (int i = 0; i < boxCount; i++) {
            String path = buf.readUtf(96);
            boxes.add(new BodyPlan.Box(i, path,
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat()));
        }

        int jointCount = buf.readVarInt();
        List<BodyPlan.Joint> joints = new ArrayList<>(jointCount);
        for (int i = 0; i < jointCount; i++) {
            joints.add(new BodyPlan.Joint(buf.readVarInt(), buf.readVarInt()));
        }

        return new BodyPlan(List.copyOf(boxes), List.copyOf(joints));
    }
}
