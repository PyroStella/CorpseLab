package com.pyrostella.corpselab.api.model;

import java.util.List;

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
 * <p>单位统一为<b>方块</b>（模型像素 / 16）。坐标是模型空间：原点在实体脚底中心
 * +Y 向上，+Z 朝南。</p>
 */
public record BodyPlan(List<Box> boxes, List<Joint> joints) {

    /**
     * 一个刚体盒子
     *
     * @param index  在列表中的下标，关节用它引用
     * @param path   来源路径（如 {@code head} / {@code body.rightFrontLeg}），仅用于调试
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
     * 一条关节，连接子盒与父盒。锚点由两个盒子的中心决定，不需要额外数据
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
