package com.pyrostella.corpselab.client.model;

import com.pyrostella.corpselab.api.model.BodyPlan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.model.ArmorStandModel;
import net.minecraft.client.model.BatModel;
import net.minecraft.client.model.ChickenModel;
import net.minecraft.client.model.CreeperModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.FoxModel;
import net.minecraft.client.model.GhastModel;
import net.minecraft.client.model.HoglinModel;
import net.minecraft.client.model.HorseModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.IronGolemModel;
import net.minecraft.client.model.OcelotModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.model.RavagerModel;
import net.minecraft.client.model.SnowGolemModel;
import net.minecraft.client.model.SpiderModel;
import net.minecraft.client.model.SquidModel;
import net.minecraft.client.model.StriderModel;
import net.minecraft.client.model.TurtleModel;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.WolfModel;

/**
 * 原版生物的<strong>骨架拓补表</strong>：哪个部件长在哪个部件上
 *
 * <p>原版模型树本身不含这个信息：僵尸的腿、牛的腿、鸡的嘴都是根节点的兄弟
 * 模型自己的 {@code children} 里根本没有"腿属于身体"。只按 children 建关节
 * 这些生物的尸体会散成一地互不相连的盒子。</p>
 *
 * <p>这里的手写表参照物理 mod “真实物理” 的 {@code VanillaRagdollHook} 的关节数据整理
 * 那里的做法是遍历 {@code headParts()}/{@code bodyParts()} 得到固定的 cuboid 序号再连线
 * 这里换成了部件名——名字来自两处，都是生产环境也稳定的：</p>
 * <ul>
 *   <li>层级模型（蜘蛛、雪傀儡…）直接用 mesh 里 children 的键名；</li>
 *   <li>可长大模型（人形、四足…）没有键名，于是按 {@code headParts()}/{@code bodyParts()}
 *       的固定下标给出名字，见各条目的注释。</li>
 * </ul>
 *
 * <p>表里没有的模型（含 mod 新增生物）不会因此散架：先照抄模型树，再按几何关系自动补全
 * 补全规则见 {@link #resolve(Table, List, List)}。</p>
 */
public final class ModelTopology {

    /** 一条关节声明：{@code child} 挂在 {@code parent} 上 */
    public record Bone(String child, String parent) {
    }

    /** 某个模型的拓补：各表顺序对应 {@code headParts()}/{@code bodyParts()}，为空表示不按表命名 */
    public record Table(String[] heads, String[] bodies, List<Bone> bones) {

        public static final Table EMPTY = new Table(new String[0], new String[0], List.of());
    }

    /** 只声明名字、不声明关节（子类用它可以整体重排父类的命名） */
    private record Family(String[] heads, String[] bodies, List<Bone> bones) {
    }

    /** 名字表里没写到的部件用 {@code prefix + 下标} 兜底 */
    private static final String HEAD_FALLBACK = "head";
    private static final String BODY_FALLBACK = "body";

    /** 盒子之间毫无重叠时，枢轴离目标盒子最远多少格还允许连上 */
    private static final double MAX_GAP = 0.75D;

    private static final Map<Class<?>, Family> FAMILIES = new LinkedHashMap<>();
    private static final Map<Class<?>, Table> CACHE = new HashMap<>();

    private ModelTopology() {
    }

    static {
        // ---------------------------------------------------------------- 人形
        // HumanoidModel.bodyParts = [body, rightArm, leftArm, rightLeg, leftLeg, hat]
        FAMILIES.put(HumanoidModel.class, new Family(
                names("head"),
                names("body", "right_arm", "left_arm", "right_leg", "left_leg", "hat"),
                bones(
                        bone("head", "body"),
                        bone("right_arm", "body"),
                        bone("left_arm", "body"),
                        bone("right_leg", "body"),
                        bone("left_leg", "body"),
                        // 帽子是头的装饰层，跟着头转
                        bone("hat", "head"))));

        // PlayerModel.bodyParts = 父类 + [leftPants, rightPants, leftSleeve, rightSleeve, jacket]
        FAMILIES.put(PlayerModel.class, new Family(
                noNames(),
                names("body", "right_arm", "left_arm", "right_leg", "left_leg", "hat",
                        "left_pants", "right_pants", "left_sleeve", "right_sleeve", "jacket"),
                bones(
                        bone("left_pants", "left_leg"),
                        bone("right_pants", "right_leg"),
                        bone("left_sleeve", "left_arm"),
                        bone("right_sleeve", "right_arm"),
                        bone("jacket", "body"))));

        // ArmorStandModel.bodyParts = 父类 + [rightBodyStick, leftBodyStick, shoulderStick, basePlate]
        FAMILIES.put(ArmorStandModel.class, new Family(
                noNames(),
                names("body", "right_arm", "left_arm", "right_leg", "left_leg", "hat",
                        "right_body_stick", "left_body_stick", "shoulder_stick", "base_plate"),
                bones(
                        bone("right_body_stick", "body"),
                        bone("left_body_stick", "body"),
                        bone("shoulder_stick", "body"),
                        bone("base_plate", "body"))));

        // ---------------------------------------------------------------- 四足
        // QuadrupedModel.bodyParts = [body, rightHindLeg, leftHindLeg, rightFrontLeg, leftFrontLeg]
        FAMILIES.put(QuadrupedModel.class, new Family(
                names("head"),
                names("body", "right_hind_leg", "left_hind_leg", "right_front_leg", "left_front_leg"),
                bones(
                        bone("head", "body"),
                        bone("right_hind_leg", "body"),
                        bone("left_hind_leg", "body"),
                        bone("right_front_leg", "body"),
                        bone("left_front_leg", "body"))));

        // TurtleModel.bodyParts = 父类 + [eggBelly]
        FAMILIES.put(TurtleModel.class, new Family(
                noNames(),
                names("body", "right_hind_leg", "left_hind_leg", "right_front_leg", "left_front_leg",
                        "egg_belly"),
                bones(bone("egg_belly", "body"))));

        // ---------------------------------------------------------------- 鸡
        // ChickenModel.headParts = [head, beak, redThing]，bodyParts = [body, rightLeg, leftLeg, rightWing, leftWing]
        FAMILIES.put(ChickenModel.class, new Family(
                names("head", "beak", "red_thing"),
                names("body", "right_leg", "left_leg", "right_wing", "left_wing"),
                bones(
                        bone("beak", "head"),
                        bone("red_thing", "head"),
                        bone("head", "body"),
                        bone("right_leg", "body"),
                        bone("left_leg", "body"),
                        bone("right_wing", "body"),
                        bone("left_wing", "body"))));

        // ---------------------------------------------------------------- 狼
        // WolfModel.bodyParts = [body, 四条腿, tail, upperBody]；头挂在 upperBody 上，相当于脖子
        FAMILIES.put(WolfModel.class, new Family(
                names("head"),
                names("body", "right_hind_leg", "left_hind_leg", "right_front_leg", "left_front_leg",
                        "tail", "upper_body"),
                bones(
                        bone("head", "upper_body"),
                        bone("upper_body", "body"),
                        bone("right_hind_leg", "body"),
                        bone("left_hind_leg", "body"),
                        bone("right_front_leg", "body"),
                        bone("left_front_leg", "body"),
                        bone("tail", "body"))));

        // ---------------------------------------------------------------- 猫
        // OcelotModel.bodyParts = [body, leftHindLeg, rightHindLeg, leftFrontLeg, rightFrontLeg, tail1, tail2]
        FAMILIES.put(OcelotModel.class, new Family(
                names("head"),
                names("body", "left_hind_leg", "right_hind_leg", "left_front_leg", "right_front_leg",
                        "tail1", "tail2"),
                bones(
                        bone("head", "body"),
                        bone("left_hind_leg", "body"),
                        bone("right_hind_leg", "body"),
                        bone("left_front_leg", "body"),
                        bone("right_front_leg", "body"),
                        bone("tail1", "body"),
                        // 尾巴两节，第二节挂在第一节上
                        bone("tail2", "tail1"))));

        // ---------------------------------------------------------------- 狐狸
        // FoxModel.bodyParts = [body, rightHindLeg, leftHindLeg, rightFrontLeg, leftFrontLeg]
        FAMILIES.put(FoxModel.class, new Family(
                names("head"),
                names("body", "right_hind_leg", "left_hind_leg", "right_front_leg", "left_front_leg"),
                bones(
                        bone("head", "body"),
                        bone("right_hind_leg", "body"),
                        bone("left_hind_leg", "body"),
                        bone("right_front_leg", "body"),
                        bone("left_front_leg", "body"))));

        // ---------------------------------------------------------------- 疣猪兽
        // HoglinModel.bodyParts = [body, rightFrontLeg, leftFrontLeg, rightHindLeg, leftHindLeg]
        FAMILIES.put(HoglinModel.class, new Family(
                names("head"),
                names("body", "right_front_leg", "left_front_leg", "right_hind_leg", "left_hind_leg"),
                bones(
                        bone("head", "body"),
                        bone("right_front_leg", "body"),
                        bone("left_front_leg", "body"),
                        bone("right_hind_leg", "body"),
                        bone("left_hind_leg", "body"))));

        // ---------------------------------------------------------------- 马
        // HorseModel.headParts = [headParts]，bodyParts = [body, 四条腿, 四条幼崽腿]
        FAMILIES.put(HorseModel.class, new Family(
                names("head_parts"),
                names("body", "right_hind_leg", "left_hind_leg", "right_front_leg", "left_front_leg",
                        "right_hind_baby_leg", "left_hind_baby_leg", "right_front_baby_leg",
                        "left_front_baby_leg"),
                bones(
                        bone("head_parts", "body"),
                        bone("right_hind_leg", "body"),
                        bone("left_hind_leg", "body"),
                        bone("right_front_leg", "body"),
                        bone("left_front_leg", "body"),
                        bone("right_hind_baby_leg", "body"),
                        bone("left_hind_baby_leg", "body"),
                        bone("right_front_baby_leg", "body"),
                        bone("left_front_baby_leg", "body"))));

        // ---------------------------------------------------------------- 层级模型
        // 这些模型的部件名来自 mesh 的 children 键，生产环境同样稳定

        // 苦力怕：头 + 身体 + 四条腿
        FAMILIES.put(CreeperModel.class, family(
                bone("head", "body"),
                bone("right_hind_leg", "body"),
                bone("left_hind_leg", "body"),
                bone("right_front_leg", "body"),
                bone("left_front_leg", "body")));

        // 蜘蛛：头、后体都挂在 body0 上，八条腿也全是 body0 的
        FAMILIES.put(SpiderModel.class, family(
                bone("head", "body0"),
                bone("body1", "body0"),
                bone("right_hind_leg", "body0"),
                bone("left_hind_leg", "body0"),
                bone("right_middle_hind_leg", "body0"),
                bone("left_middle_hind_leg", "body0"),
                bone("right_middle_front_leg", "body0"),
                bone("left_middle_front_leg", "body0"),
                bone("right_front_leg", "body0"),
                bone("left_front_leg", "body0")));

        // 铁傀儡：头、双臂、双腿都挂在身体上
        FAMILIES.put(IronGolemModel.class, family(
                bone("head", "body"),
                bone("right_arm", "body"),
                bone("left_arm", "body"),
                bone("right_leg", "body"),
                bone("left_leg", "body")));

        // 雪傀儡：头与双臂挂在 upper_body，upper_body 站在 lower_body 上
        FAMILIES.put(SnowGolemModel.class, family(
                bone("head", "upper_body"),
                bone("right_arm", "upper_body"),
                bone("left_arm", "upper_body"),
                bone("upper_body", "lower_body")));

        // 蝙蝠：头挂在身体上（耳朵、翅膀本来就是 mesh 的子部件）
        FAMILIES.put(BatModel.class, family(bone("head", "body")));

        // 炽足兽：两条腿是身体的兄弟
        FAMILIES.put(StriderModel.class, family(
                bone("right_leg", "body"),
                bone("left_leg", "body")));

        // 劫掠兽：脖子挂在身体上（头是脖子的子部件），四条腿挂身体
        FAMILIES.put(RavagerModel.class, family(
                bone("neck", "body"),
                bone("right_hind_leg", "body"),
                bone("left_hind_leg", "body"),
                bone("right_front_leg", "body"),
                bone("left_front_leg", "body")));

        // 村民：双臂（arms 是一整块）与双腿挂身体；头、帽子、鼻子、外套本来就是子部件
        FAMILIES.put(VillagerModel.class, family(
                bone("head", "body"),
                bone("arms", "body"),
                bone("right_leg", "body"),
                bone("left_leg", "body")));

        // 灾厄村民：双臂双腿挂身体
        FAMILIES.put(IllagerModel.class, family(
                bone("head", "body"),
                bone("arms", "body"),
                bone("right_arm", "body"),
                bone("left_arm", "body"),
                bone("right_leg", "body"),
                bone("left_leg", "body")));

        // 鱿鱼 / 恶魂：触手是身体根部件下的兄弟
        FAMILIES.put(SquidModel.class, tentacles("body", 8));
        FAMILIES.put(GhastModel.class, tentacles("body", 9));
    }

    /** 登记过的模型类，仅供测试遍历（顺序即登记顺序） */
    static java.util.Set<Class<?>> registeredTypes() {
        return java.util.Collections.unmodifiableSet(FAMILIES.keySet());
    }

    /** 取该模型的拓补（含父类声明，子类优先） */
    public static Table tableFor(EntityModel<?> model) {
        return tableFor(model.getClass());
    }

    /** 取该模型的拓补（含父类声明，子类优先） */
    public static Table tableFor(Class<?> type) {
        synchronized (CACHE) {
            Table cached = CACHE.get(type);
            if (cached == null) {
                cached = build(type);
                CACHE.put(type, cached);
            }
            return cached;
        }
    }

    private static Table build(Class<?> type) {
        String[] heads = null;
        String[] bodies = null;
        List<Bone> bones = new ArrayList<>();

        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            Family family = FAMILIES.get(current);
            if (family == null) {
                continue;
            }
            if (heads == null && family.heads().length > 0) {
                heads = family.heads();
            }
            if (bodies == null && family.bodies().length > 0) {
                bodies = family.bodies();
            }
            bones.addAll(family.bones());
        }

        return new Table(
                heads == null ? new String[0] : heads,
                bodies == null ? new String[0] : bodies,
                List.copyOf(bones));
    }

    /** 该模型的 {@code headParts()} 该怎么起名字 */
    public static String headName(Table table, int index) {
        return index < table.heads().length ? table.heads()[index] : HEAD_FALLBACK + index;
    }

    /** 该模型的 {@code bodyParts()} 该怎么起名字 */
    public static String bodyName(Table table, int index) {
        return index < table.bodies().length ? table.bodies()[index] : BODY_FALLBACK + index;
    }

    /**
     * 把三路来源合成最终关节表
     *
     * <p>顺序：模型树 → 手写拓补表 → 几何补全。一个盒子只有一个父节点
     * 先到先得，所以模型树里已经写好的关系不会被表覆盖。</p>
     *
     * <p>几何补全针对的是表里没写的部件（装饰层、mod 生物）：对所有盒子两两打分
     * 重叠体积大的优先，其次枢轴离对方盒子近的优先，用并查集避免成环
     * 于是帽子会贴到头上、裤子的贴图层会贴到腿上，而不是各自飘在半空。</p>
     */
    public static List<BodyPlan.Joint> resolve(EntityModel<?> model, List<BodyPlan.Box> boxes,
                                               List<BodyPlan.Joint> treeJoints) {
        return resolve(tableFor(model), boxes, treeJoints);
    }

    static List<BodyPlan.Joint> resolve(Table table, List<BodyPlan.Box> boxes,
                                        List<BodyPlan.Joint> treeJoints) {
        int count = boxes.size();
        if (count < 2) {
            return List.of();
        }

        int[] parentOf = new int[count];
        Arrays.fill(parentOf, -1);

        // 部件名 → 盒子。同名时取扫描顺序里的第一个（模型树本来就是先序遍历）
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < count; i++) {
            byName.putIfAbsent(leaf(boxes.get(i).path()), i);
        }

        // 1. 模型树自己的父子关系
        for (BodyPlan.Joint joint : treeJoints) {
            int child = joint.child();
            int parent = joint.parent();
            if (child < 0 || child >= count || parent < 0 || parent >= count || child == parent) {
                continue;
            }
            if (parentOf[child] < 0) {
                parentOf[child] = parent;
            }
        }

        // 2. 手写拓补表
        for (Bone bone : table.bones()) {
            Integer child = byName.get(bone.child());
            Integer parent = byName.get(bone.parent());
            if (child == null || parent == null || child.equals(parent)) {
                continue;
            }
            if (parentOf[child] >= 0 || descends(parentOf, parent, child)) {
                continue;
            }
            parentOf[child] = parent;
        }

        // 3. 剩下的按几何关系补
        attachOrphans(boxes, parentOf);

        List<BodyPlan.Joint> joints = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (parentOf[i] >= 0) {
                joints.add(new BodyPlan.Joint(i, parentOf[i]));
            }
        }
        return List.copyOf(joints);
    }

    /** 一条候选连接，{@code overlap} 越大越好，{@code gap} 越小越好 */
    private record Edge(int child, int parent, double overlap, double gap,
                        double parentVolume, double parentCentreY) {
    }

    /**
     * 给还没有父节点的盒子找父节点：重叠体积 → 枢轴间隙 → 体积大的当父节点
     *
     * <p>已经是别人的父节点的盒子不参与——那多半是躯干，让它回头挂到四肢上会把整棵树掀翻。</p>
     */
    private static void attachOrphans(List<BodyPlan.Box> boxes, int[] parentOf) {
        int count = boxes.size();
        List<Edge> edges = new ArrayList<>();

        // 已经在带孩子的盒子 = 各棵树的根，不能再给别人当孩子
        boolean[] isParent = new boolean[count];
        for (int index = 0; index < count; index++) {
            if (parentOf[index] >= 0) {
                isParent[parentOf[index]] = true;
            }
        }

        for (int child = 0; child < count; child++) {
            if (isParent[child]) {
                continue;
            }
            BodyPlan.Box box = boxes.get(child);
            for (int parent = 0; parent < count; parent++) {
                if (child == parent) {
                    continue;
                }
                BodyPlan.Box candidate = boxes.get(parent);
                double overlap = overlapVolume(box, candidate);
                double gap = pivotGap(box, candidate);
                if (overlap <= 0.0D && gap > MAX_GAP) {
                    continue;
                }
                edges.add(new Edge(child, parent, overlap, gap, volume(candidate), candidate.cy()));
            }
        }

        edges.sort((a, b) -> {
            int byOverlap = Double.compare(b.overlap(), a.overlap());
            if (byOverlap != 0) {
                return byOverlap;
            }
            int byGap = Double.compare(a.gap(), b.gap());
            if (byGap != 0) {
                return byGap;
            }
            int byVolume = Double.compare(b.parentVolume(), a.parentVolume());
            if (byVolume != 0) {
                return byVolume;
            }
            // 一样大就让位置低的那颗当父节点：躯干通常长在下面
            int byHeight = Double.compare(a.parentCentreY(), b.parentCentreY());
            if (byHeight != 0) {
                return byHeight;
            }
            int byChild = Integer.compare(a.child(), b.child());
            return byChild != 0 ? byChild : Integer.compare(a.parent(), b.parent());
        });

        // 并查集：同一棵树里的两个盒子不能再连，否则关节环会让物理求解原地打转
        int[] set = new int[count];
        for (int i = 0; i < count; i++) {
            set[i] = i;
        }

        for (Edge edge : edges) {
            if (parentOf[edge.child()] >= 0) {
                continue;
            }
            int childSet = find(set, edge.child());
            int parentSet = find(set, edge.parent());
            if (childSet == parentSet) {
                continue;
            }
            parentOf[edge.child()] = edge.parent();
            set[childSet] = parentSet;
        }
    }

    private static int find(int[] set, int index) {
        int root = index;
        while (set[root] != root) {
            root = set[root];
        }
        int walk = index;
        while (set[walk] != walk) {
            int next = set[walk];
            set[walk] = root;
            walk = next;
        }
        return root;
    }

    /** {@code from} 是否在 {@code target} 的子树里（用于防止连成环） */
    private static boolean descends(int[] parentOf, int from, int target) {
        int current = from;
        for (int guard = 0; guard <= parentOf.length && current >= 0; guard++) {
            if (current == target) {
                return true;
            }
            current = parentOf[current];
        }
        return false;
    }

    private static double volume(BodyPlan.Box box) {
        return 8.0D * box.hx() * box.hy() * box.hz();
    }

    /** 两个盒子的 AABB 重叠体积 */
    private static double overlapVolume(BodyPlan.Box a, BodyPlan.Box b) {
        double x = Math.min(a.cx() + a.hx(), b.cx() + b.hx()) - Math.max(a.cx() - a.hx(), b.cx() - b.hx());
        double y = Math.min(a.cy() + a.hy(), b.cy() + b.hy()) - Math.max(a.cy() - a.hy(), b.cy() - b.hy());
        double z = Math.min(a.cz() + a.hz(), b.cz() + b.hz()) - Math.max(a.cz() - a.hz(), b.cz() - b.hz());
        if (x <= 0.0D || y <= 0.0D || z <= 0.0D) {
            return 0.0D;
        }
        return x * y * z;
    }

    /** 子盒子的枢轴到父盒子 AABB 的距离，用来给不重叠的部件排序 */
    private static double pivotGap(BodyPlan.Box child, BodyPlan.Box parent) {
        double dx = axisGap(child.px(), parent.cx(), parent.hx());
        double dy = axisGap(child.py(), parent.cy(), parent.hy());
        double dz = axisGap(child.pz(), parent.cz(), parent.hz());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double axisGap(double value, double centre, double half) {
        double offset = Math.abs(value - centre) - half;
        return Math.max(0.0D, offset);
    }

    /** 路径最后一段，例如 {@code body.hat_rim} → {@code hat_rim} */
    private static String leaf(String path) {
        int dot = path.lastIndexOf('.');
        String leaf = dot < 0 ? path : path.substring(dot + 1);
        return leaf.toLowerCase(Locale.ROOT);
    }

    private static Family family(Bone... bones) {
        return new Family(noNames(), noNames(), bones(bones));
    }

    /** 触手类的部件：{@code tentacle0..n-1} 全挂在身体上 */
    private static Family tentacles(String body, int count) {
        List<Bone> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(bone("tentacle" + i, body));
        }
        return new Family(noNames(), noNames(), List.copyOf(list));
    }

    private static Bone bone(String child, String parent) {
        return new Bone(child, parent);
    }

    private static List<Bone> bones(Bone... bones) {
        return List.of(bones);
    }

    private static String[] names(String... names) {
        return names;
    }

    private static String[] noNames() {
        return new String[0];
    }
}
