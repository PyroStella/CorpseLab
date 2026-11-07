package com.pyrostella.corpselab.internal.corpse;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.internal.menu.CorpseMenu;

import com.pyrostella.corpselab.internal.registry.CorpseLabRegistry;
import com.pyrostella.corpselab.internal.config.CorpseLabConfig;
import com.pyrostella.corpselab.internal.corpse.CorpseSourceData;
import com.pyrostella.corpselab.internal.skeleton.LimbPose;
import com.pyrostella.corpselab.internal.skeleton.RagdollSkeleton;
import net.minecraft.core.NonNullList;
import com.pyrostella.corpselab.api.event.CorpseRemovalEvent.Cause;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * 玩家死亡后留下的东西：他们的尸体，仍然带着随身物品
 *
 * <p>尸体会跨重启保留，并最终固定为一个姿态而不是被永远模拟
 * ——一旦停止移动就没有任何东西需要计算，于是完全停止步进
 * 并保持最终姿态
 */
public class CorpseEntity extends SettlingBody {

    /**
     * 大小按原版六行箱子确定，而不是按玩家的 41 格
     * 只有 threeRows 和 sixRows 接受容器，而 41 格放不进三行
     * 因此尸体可以直接用 Minecraft 自带的容器 UI 打开，完全不需要自定义界面
     * 41 到 53 号槽位保持为空
     */
    public static final int SLOT_COUNT = 54;
    private CompoundTag sourceEntityData = new CompoundTag();

    private static final int ARMOUR_START = 36;
    private static final int OFFHAND_SLOT = 40;

    /**
     * 尸体身上可见地穿着和拿着的东西
     *
     * <p>容器本身永远到不了客户端，因为 SimpleContainer 属于服务端状态
     * 只靠容器渲染出来的尸体就是一个光着身子的角色。这六个槽位会同步
     * 让尸体看起来像死去的那个玩家，并随着被搜刮而更新
     * 所以拿走头盔就会露出头部
     */
    private static final EntityDataAccessor<ItemStack> WORN_HEAD = worn();
    private static final EntityDataAccessor<ItemStack> WORN_CHEST = worn();
    private static final EntityDataAccessor<ItemStack> WORN_LEGS = worn();
    private static final EntityDataAccessor<ItemStack> WORN_FEET = worn();
    private static final EntityDataAccessor<ItemStack> HELD_MAIN = worn();
    private static final EntityDataAccessor<ItemStack> HELD_OFF = worn();

    private static EntityDataAccessor<ItemStack> worn() {
        return SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.ITEM_STACK);
    }

    private final SimpleContainer contents = new SimpleContainer(SLOT_COUNT);

    /** 玩家当时拿的是哪个快捷栏槽位，好让尸体继续拿着同样的东西 */
    private int mainHandSlot = -1;

    private String ownerName = "";

    /**
     * 这具尸体是否曾经装过东西。没有它的话，在 keepInventory 下死亡会创建一个
     * 空尸体，并在第一个 tick 就消失
     */
    private boolean everHadItems;
    private int equipmentSyncDepth;
    private boolean equipmentSyncPending;

    public CorpseEntity(EntityType<? extends CorpseEntity> type, Level level) {
        super(type, level);
        // 通过容器界面搜刮会改变内容物而不会经过
        // 这个类，所以同步必须由容器自身来驱动
        contents.addListener(container -> syncEquipment());
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(WORN_HEAD, ItemStack.EMPTY);
        entityData.define(WORN_CHEST, ItemStack.EMPTY);
        entityData.define(WORN_LEGS, ItemStack.EMPTY);
        entityData.define(WORN_FEET, ItemStack.EMPTY);
        entityData.define(HELD_MAIN, ItemStack.EMPTY);
        entityData.define(HELD_OFF, ItemStack.EMPTY);
    }

    /** 把可见槽位从容器里复制出来并发到网络上 */
    private void syncEquipment() {
        if (level().isClientSide) {
            return;
        }
        if (equipmentSyncDepth > 0) {
            equipmentSyncPending = true;
            return;
        }
        entityData.set(WORN_FEET, contents.getItem(ARMOUR_START).copy());
        entityData.set(WORN_LEGS, contents.getItem(ARMOUR_START + 1).copy());
        entityData.set(WORN_CHEST, contents.getItem(ARMOUR_START + 2).copy());
        entityData.set(WORN_HEAD, contents.getItem(ARMOUR_START + 3).copy());
        entityData.set(HELD_OFF, contents.getItem(OFFHAND_SLOT).copy());
        entityData.set(HELD_MAIN, mainHandSlot >= 0 && mainHandSlot < ARMOUR_START
                ? contents.getItem(mainHandSlot).copy()
                : ItemStack.EMPTY);
    }

    /** 批量搬运只合并装备同步，不抑制容器的外部监听与失败回退。 */
    private void mutateContents(Runnable operation) {
        equipmentSyncDepth++;
        try {
            operation.run();
        } finally {
            if (--equipmentSyncDepth == 0 && equipmentSyncPending) {
                equipmentSyncPending = false;
                syncEquipment();
            }
        }
    }

    /** 在玩家刚刚死亡的位置创建尸体，并带上他们当时的姿态与运动状态 */
    public static CorpseEntity of(Player player, Vec3 momentum) {
        Level level = player.level();
        CorpseEntity corpse = new CorpseEntity(CorpseLabRegistry.CORPSE.get(), level);

        corpse.sourceEntityData = CorpseSourceData.capture(player);
        Vec3 feet = player.position();
        corpse.setPos(feet.x, feet.y, feet.z);
        corpse.setOwnerId(player.getUUID());
        corpse.ownerName = player.getGameProfile().getName();

        RagdollSkeleton skeleton = new RagdollSkeleton(feet.x, feet.y, feet.z,
                RagdollSkeleton.facingFromYaw(player.yBodyRot),
                CorpseLabConfig.SERVER.partSelfCollision.get(),
                LimbPose.of(player));
        skeleton.setCollider(new LevelCollider(level));
        skeleton.world().maxSpeed = CorpseLabConfig.SERVER.maxLaunchSpeed.get();
        skeleton.launch(new Vector3d(momentum.x, momentum.y, momentum.z));

        corpse.setSkeleton(skeleton);
        return corpse;
    }

    /** 保存死亡时包含原 UUID、物品栏和模组附加数据的完整副本 */
    public CompoundTag sourceEntityData() {
        return this.sourceEntityData.copy();
    }

    public SimpleContainer contents() {
        return contents;
    }

    public String ownerName() {
        return ownerName;
    }

    /** 把死亡玩家的整个背包搬进尸体里 */
    public void takeInventoryFrom(Player player) {
        mutateContents(() -> {
            for (int i = 0; i < player.getInventory().items.size() && i < ARMOUR_START; i++) {
                contents.setItem(i, player.getInventory().items.get(i).copy());
                player.getInventory().items.set(i, ItemStack.EMPTY);
            }
            for (int i = 0; i < player.getInventory().armor.size(); i++) {
                contents.setItem(ARMOUR_START + i, player.getInventory().armor.get(i).copy());
                player.getInventory().armor.set(i, ItemStack.EMPTY);
            }
            contents.setItem(OFFHAND_SLOT, player.getInventory().offhand.get(0).copy());
            player.getInventory().offhand.set(0, ItemStack.EMPTY);

            mainHandSlot = player.getInventory().selected;
            everHadItems = !contents.isEmpty();
            syncEquipment();
        });
    }

    public boolean isEmpty() {
        return contents.isEmpty();
    }

    @Override
    protected void checkLifetime() {
        if (everHadItems && isEmpty() && CorpseLabConfig.SERVER.corpseDespawnWhenEmpty.get()) {
            CorpseLifecycle.discard(this, Cause.EMPTY);
            return;
        }

        int lifetime = CorpseLabConfig.SERVER.corpseLifetimeTicks.get();
        // 零表示永不超时，因此尸体只有在被搜刮后才会移除
        if (lifetime > 0 && age() >= lifetime) {
            dropEverything();
            CorpseLifecycle.discard(this, Cause.EXPIRED);
        }
    }

    /** 把剩下的东西撒出来，让超时消失的尸体不会悄悄吞掉别人的物品 */
    private void dropEverything() {
        mutateContents(() -> {
            for (int i = 0; i < contents.getContainerSize(); i++) {
                ItemStack stack = contents.getItem(i);
                if (!stack.isEmpty()) {
                    spawnAtLocation(stack);
                    contents.setItem(i, ItemStack.EMPTY);
                }
            }
        });
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            equipOnto(player);
        } else {
            CorpseMenu.open(player, this);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * 把所有东西放回死亡玩家原来所在的位置，盔甲穿好、副手填满
     * 每件物品则放回它来自的槽位，前提是该槽位仍然空着
     */
    public void equipOnto(Player player) {
        mutateContents(() -> {
            var inventory = player.getInventory();
            for (int i = 0; i < inventory.armor.size(); i++) {
                moveInto(player, ARMOUR_START + i, inventory.armor, i);
            }
            moveInto(player, OFFHAND_SLOT, inventory.offhand, 0);
            for (int i = 0; i < ARMOUR_START; i++) {
                moveInto(player, i, inventory.items, i);
            }
        });
        checkLifetime();
    }

    private void moveInto(Player player, int corpseSlot, NonNullList<ItemStack> destination, int index) {
        ItemStack stack = contents.getItem(corpseSlot);
        if (stack.isEmpty()) return;
        if (destination.get(index).isEmpty()) {
            destination.set(index, stack);
            contents.setItem(corpseSlot, ItemStack.EMPTY);
        } else if (player.getInventory().add(stack)) {
            contents.setItem(corpseSlot, ItemStack.EMPTY);
        }
    }

    public Component displayTitle() {
        return Component.translatable(CorpseLab.MOD_ID + ".container.corpse", ownerName);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.sourceEntityData = tag.getCompound("SourceEntityData").copy();
        ownerName = tag.getString("OwnerName");
        setAge(tag.getInt("Age"));
        everHadItems = tag.getBoolean("HadItems");
        mainHandSlot = tag.getInt("MainHandSlot");

        if (tag.hasUUID("Owner")) {
            setOwnerId(tag.getUUID("Owner"));
        }

        mutateContents(() -> {
            contents.clearContent();
            ListTag items = tag.getList("Items", 10);
            for (int i = 0; i < items.size(); i++) {
                CompoundTag entry = items.getCompound(i);
                int slot = entry.getByte("Slot") & 255;
                if (slot < contents.getContainerSize()) contents.setItem(slot, ItemStack.of(entry));
            }
        });

        RagdollSkeleton skeleton = new RagdollSkeleton(getX(), getY(), getZ(), 0.0D,
                false, LimbPose.STANDING);
        skeleton.setCollider(new LevelCollider(level()));
        setSkeleton(skeleton);

        // 恢复它定格时的姿态，否则重载后的尸体会重新站起来
        BodyPose.read(tag, skeleton);

        setFrozen(true);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("SourceEntityData", this.sourceEntityData.copy());
        tag.putString("OwnerName", ownerName);
        tag.putInt("Age", age());

        UUID owner = getOwnerId();
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }

        ListTag items = new ListTag();
        for (int i = 0; i < contents.getContainerSize(); i++) {
            ItemStack stack = contents.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag entry = new CompoundTag();
                entry.putByte("Slot", (byte) i);
                stack.save(entry);
                items.add(entry);
            }
        }
        tag.put("Items", items);
        tag.putBoolean("HadItems", everHadItems);
        tag.putInt("MainHandSlot", mainHandSlot);

        BodyPose.write(tag, skeleton());
    }

    /**
     * 显示在尸体身上的装备。从同步数据而不是容器读取
     * 因此在没有容器的客户端上也是正确的
     */
    public ItemStack equipment(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> entityData.get(WORN_HEAD);
            case CHEST -> entityData.get(WORN_CHEST);
            case LEGS -> entityData.get(WORN_LEGS);
            case FEET -> entityData.get(WORN_FEET);
            case OFFHAND -> entityData.get(HELD_OFF);
            case MAINHAND -> entityData.get(HELD_MAIN);
        };
    }
}
