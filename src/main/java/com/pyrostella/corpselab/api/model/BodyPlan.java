package com.pyrostella.corpselab.api.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一具尸体的物理骨架描述：若干个盒子 + 若干条关节
 *
 * <p>这是<b>纯数据</b>，不含任何 Minecraft 类型，也不含物理求解器类型。设计目的有三：</p>
 * <ol>
 *   <li>客户端能从模型算出来，服务端能照着跑物理，两边用同一份数据；</li>
 *   <li>原版模型和 geo 模型都能生成它，物理层不需要知道区别；</li>
 *   <li>能序列化后发给服务端（后续做"客户端报价、服务端模拟"）。</li>
 * </ol>
 *
 * <p>单位为格（模型像素 / 16），保存渲染模型坐标。与脚底原点、Y 向上的
 * 公开构建器坐标之间转换为 {@code (-x, 1.501-y, z)}，转换自身为逆运算。</p>
 */
public record BodyPlan(List<Box> boxes, List<Joint> joints) {
    public BodyPlan {
        boxes = List.copyOf(boxes);
        joints = List.copyOf(joints);
    }

    /** 空骨架可供内部占位；正式创建还必须要求至少一个部件 */
    public boolean isValid() {
        Set<String> paths = new HashSet<>();
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            if (box.index() != i || box.path() == null || box.path().isBlank()
                    || !paths.add(box.path()) || !validBox(box)) return false;
        }
        int[] components = new int[boxes.size()];
        boolean[] hasParent = new boolean[boxes.size()];
        for (int i = 0; i < components.length; i++) components[i] = i;
        for (Joint joint : joints) {
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

    /** 体积最大部件的下标，体积相同时保留首个；空骨架返回 -1 */
    public int largestPartIndex() {
        int index = -1;
        double volume = -1;
        for (Box box : boxes) {
            double current = box.hx() * box.hy() * box.hz();
            if (current > volume) {
                volume = current;
                index = box.index();
            }
        }
        return index;
    }

    private static int root(int[] components, int index) {
        while (components[index] != index) {
            components[index] = components[components[index]];
            index = components[index];
        }
        return index;
    }

    private static boolean validBox(Box box) {
        return finite(box.cx()) && finite(box.cy()) && finite(box.cz())
                && finite(box.hx()) && finite(box.hy()) && finite(box.hz())
                && finite(box.mass()) && finite(box.px()) && finite(box.py()) && finite(box.pz())
                && box.hx() >= 0 && box.hy() >= 0 && box.hz() >= 0 && (float) box.mass() > 0;
    }

    private static boolean finite(double value) {
        return Double.isFinite(value) && Float.isFinite((float) value);
    }


    /**
     * 一个刚体盒子
     *
     * @param index  在列表中的下标，关节用它引用
     * @param path   稳定部件 ID（如 {@code head} / {@code body.rightFrontLeg}），供渲染与复活共同引用
     * @param cx     中心 X（方块）
     * @param cy     中心 Y
     * @param cz     中心 Z
     * @param hx     半宽（X 方向）
     * @param hy     半高
     * @param hz     半深
     * @param mass   质量，按体积估算
     * @param px     部件的枢轴 X（方块）。原版部件绕自己的原点旋转，关节也长在这里
     *               所以姿势继承与关节锚点都要用它，不能用盒子中心
     * @param py     枢轴 Y
     * @param pz     枢轴 Z
     */
    public record Box(int index, String path,
                      double cx, double cy, double cz,
                      double hx, double hy, double hz, double mass,
                      double px, double py, double pz) {
    }

    /**
     * 一条关节，连接子盒与父盒。锚点使用子部件的枢轴
     *
     * @param child  子盒下标
     * @param parent 父盒下标
     */
    public record Joint(int child, int parent) {
    }

    public int boxCount() {
        return this.boxes.size();
    }

    public int jointCount() {
        return this.joints.size();
    }

    /** 多行可读文本，用于日志与聊天栏输出 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("boxes=").append(this.boxes.size())
                .append(" joints=").append(this.joints.size());
        for (Box box : this.boxes) {
            sb.append("\n  #").append(box.index()).append(' ').append(box.path())
                    .append(" center=(").append(fmt(box.cx())).append(", ").append(fmt(box.cy()))
                    .append(", ").append(fmt(box.cz())).append(')')
                    .append(" half=(").append(fmt(box.hx())).append(", ").append(fmt(box.hy()))
                    .append(", ").append(fmt(box.hz())).append(')')
                    .append(" pivot=(").append(fmt(box.px())).append(", ").append(fmt(box.py()))
                    .append(", ").append(fmt(box.pz())).append(')')
                    .append(" mass=").append(fmt(box.mass()));
        }
        for (Joint joint : this.joints) {
            sb.append("\n  joint ").append(joint.child()).append(" -> ").append(joint.parent());
        }
        return sb.toString();
    }

    private static String fmt(double value) {
        return String.format("%.3f", value);
    }
}
