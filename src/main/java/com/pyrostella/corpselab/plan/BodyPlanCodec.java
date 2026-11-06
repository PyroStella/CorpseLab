package com.pyrostella.corpselab.plan;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import com.pyrostella.corpselab.util.PacketReads;
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
        if (!valid(plan)) throw new IllegalArgumentException("骨架数据无效");
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
        if (!valid(plan)) throw new DecoderException("骨架数据无效");
        return plan;
    }
    /** 保留数值和拓扑完整性检查，不按部件条数、层级深度或尺寸倍率拒绝模型 */
    public static boolean valid(BodyPlan plan) {
        for (int i = 0; i < plan.boxCount(); i++) {
            BodyPlan.Box box = plan.boxes().get(i);
            if (box.index() != i || box.path() == null || !validBox(box)) return false;
        }
        int[] components = new int[plan.boxCount()];
        boolean[] hasParent = new boolean[plan.boxCount()];
        for (int i = 0; i < components.length; i++) components[i] = i;
        for (BodyPlan.Joint joint : plan.joints()) {
            int child = joint.child(), parent = joint.parent();
            if (child < 0 || parent < 0 || child >= components.length || parent >= components.length
                    || child == parent || hasParent[child]) return false;
            hasParent[child] = true;
            int a = root(components, child), b = root(components, parent);
            if (a == b) return false;
            components[a] = b;
        }
        return true;
    }

    private static int root(int[] components, int index) {
        while (components[index] != index) {
            components[index] = components[components[index]];
            index = components[index];
        }
        return index;
    }

    private static boolean validBox(BodyPlan.Box box) {
        return finite(box.cx()) && finite(box.cy()) && finite(box.cz())
                && finite(box.hx()) && finite(box.hy()) && finite(box.hz())
                && finite(box.mass()) && finite(box.px()) && finite(box.py()) && finite(box.pz())
                && box.hx() >= 0 && box.hy() >= 0 && box.hz() >= 0 && (float)box.mass() > 0;
    }

    private static boolean finite(double value) {
        return Double.isFinite(value) && Float.isFinite((float)value);
    }
}