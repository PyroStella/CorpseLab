package com.pyrostella.corpselab.internal.menu;

import com.pyrostella.corpselab.CorpseLab;
import com.pyrostella.corpselab.internal.config.ConfigRegistry;
import com.pyrostella.corpselab.internal.config.ClientSettings;
import com.pyrostella.corpselab.internal.registry.CorpseLabItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.NetworkHooks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模组拥有的每一项设置，做成一个箱子菜单
 *
 * <p>基于原版六排箱子类型而不是自带菜单类型，因此完全没有客户端代码
 * 物品是什么、点击做什么都由服务端决定，任何客户端
 * 用它本来就有的箱子界面就能画出来，这也意味着菜单骗不了
 * 客户端可以发点击，但它发来的一切
 * 都不会被信任到超出 {@link #mayEdit} 的范围
 *
 * <p>各行都从 {@link ConfigRegistry} 读取，所以模组里新加的设置
 * 会自动出现在这里，提示框使用翻译组件，范围也会被强制执行
 */
public final class ConfigMenu extends ChestMenu {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int PAGE_SIZE = 45;

    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREVIOUS = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 50;
    /** 右下角红叉：丢弃全部暂存改动，一个字节都不写 */
    private static final int SLOT_CANCEL = 52;
    /** 右下角绿勾：把暂存改动写进配置文件并关界面 */
    private static final int SLOT_CONFIRM = 53;

    /** 原版 /gamerule 要求的等级，也是这里改动任何东西所需的等级 */
    public static final int PERMISSION_LEVEL = 2;

    private final SimpleContainer display;
    private final ServerPlayer viewer;

    /**
     * 点了但还没确认的改动：设置 → 传输形式的当前值
     *
     * <p>确认之前配置文件和客户端设置都不会被动过，所以红叉关界面
     * 天然就回到打开界面时的状态，不需要另存一份快照
     */
    private final Map<ConfigRegistry.Entry, String> pending = new LinkedHashMap<>();

    /** 索引页上为 null，否则是当前显示的分区 */
    private String section;
    private int page;

    private ConfigMenu(int id, Inventory inventory, SimpleContainer display, ServerPlayer viewer) {
        super(MenuType.GENERIC_9x6, id, inventory, display, ROWS);
        this.display = display;
        this.viewer = viewer;
    }

    public static void open(ServerPlayer player) {
        NetworkHooks.openScreen(player, new SimpleMenuProvider((id, inventory, opener) -> {
            SimpleContainer container = new SimpleContainer(SIZE);
            ConfigMenu menu = new ConfigMenu(id, inventory, container, player);
            menu.rebuild();
            return menu;
        }, Component.translatable(CorpseLab.MOD_ID + ".menu.title")));

        // 客户端自己的设置存在客户端上，只能去问它。菜单先立刻打开
        // 等答复到了再把那几行填上
        ClientSettings.request(player);
    }

    /** 重画已打开的菜单，用于玩家客户端上报设置时 */
    public void refresh() {
        rebuild();
    }

    // ---- 内容 ------------------------------------------------------------------------

    private List<ConfigRegistry.Entry> sectionEntries() {
        return ConfigRegistry.bySection().getOrDefault(section, List.of());
    }

    private int pageCount() {
        return Math.max(1, (sectionEntries().size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void rebuild() {
        for (int slot = 0; slot < SIZE; slot++) {
            display.setItem(slot, ItemStack.EMPTY);
        }

        if (section == null) {
            buildIndex();
        } else {
            buildSection();
        }
        buildNavigation();

        // 客户端按它看到的点击做了预测，所以整份菜单重发一遍
        // 而不是只发变化的部分
        sendAllDataToRemote();
    }

    private void buildIndex() {
        int slot = 0;
        for (Map.Entry<String, List<ConfigRegistry.Entry>> entry : ConfigRegistry.bySection().entrySet()) {
            if (slot >= PAGE_SIZE) {
                break;
            }
            display.setItem(slot++, ConfigItems.section(entry.getKey(), entry.getValue().size()));
        }
    }

    private void buildSection() {
        List<ConfigRegistry.Entry> entries = sectionEntries();
        int from = Math.min(page * PAGE_SIZE, entries.size());
        int to = Math.min(from + PAGE_SIZE, entries.size());

        for (int i = from; i < to; i++) {
            ConfigRegistry.Entry entry = entries.get(i);
            String value = rawValue(entry);
            display.setItem(i - from,
                    ConfigItems.setting(entry, value == null ? "" : value,
                            mayEdit(entry), value == null, pending.containsKey(entry)));
        }
    }

    private void buildNavigation() {
        if (section != null) {
            display.setItem(SLOT_BACK, ConfigItems.nav(CorpseLabItem.RED_ARROW_LEFT.get(),
                    Component.translatable(CorpseLab.MOD_ID + ".menu.back"), ChatFormatting.WHITE));
            display.setItem(SLOT_INFO, ConfigItems.nav(Items.BOOK,
                    ConfigItems.sectionName(section), ChatFormatting.YELLOW,
                    Component.translatable(CorpseLab.MOD_ID + ".menu.page", page + 1, pageCount())));
            if (page > 0) {
                display.setItem(SLOT_PREVIOUS, ConfigItems.nav(Items.SPECTRAL_ARROW,
                        Component.translatable(CorpseLab.MOD_ID + ".menu.previous"), ChatFormatting.WHITE));
            }
            if (page < pageCount() - 1) {
                display.setItem(SLOT_NEXT, ConfigItems.nav(Items.SPECTRAL_ARROW,
                        Component.translatable(CorpseLab.MOD_ID + ".menu.next"), ChatFormatting.WHITE));
            }
        } else {
            display.setItem(SLOT_INFO, ConfigItems.nav(Items.BOOK,
                    Component.translatable(CorpseLab.MOD_ID + ".menu.title"), ChatFormatting.YELLOW,
                    Component.translatable(viewer.hasPermissions(PERMISSION_LEVEL)
                            ? CorpseLab.MOD_ID + ".menu.access.admin" : CorpseLab.MOD_ID + ".menu.access.player")));
        }

        // 右下角固定是「丢弃 / 保存」，任何一页都能提交，也都能放弃
        display.setItem(SLOT_CANCEL, ConfigItems.nav(CorpseLabItem.RED_CROSS.get(),
                Component.translatable(CorpseLab.MOD_ID + ".menu.cancel"), ChatFormatting.RED));
        display.setItem(SLOT_CONFIRM, ConfigItems.nav(CorpseLabItem.GREEN_CHECK.get(),
                Component.translatable(CorpseLab.MOD_ID + ".menu.confirm"), ChatFormatting.GREEN));
    }

    // ---- 取值 ------------------------------------------------------------------------

    private boolean mayEdit(ConfigRegistry.Entry entry) {
        if (!entry.serverSide()) {
            return true;
        }
        return viewer.hasPermissions(PERMISSION_LEVEL);
    }

    /** 当前值；点了没确认时是暂存值，对这名玩家尚未上报的客户端设置则为 null */
    private String rawValue(ConfigRegistry.Entry entry) {
        String staged = pending.get(entry);
        if (staged != null) {
            return staged;
        }
        if (entry.serverSide()) {
            return ConfigRegistry.encode(ConfigRegistry.get(entry));
        }
        return ClientSettings.get(viewer, entry.path());
    }

    /** 暂存一项改动；真正落盘要等玩家点右下角的绿勾 */
    private void stage(ConfigRegistry.Entry entry, Object value) {
        pending.put(entry, ConfigRegistry.encode(value));
    }

    /** 绿勾：把暂存的改动逐项写下去，然后关界面 */
    private void commit() {
        for (Map.Entry<ConfigRegistry.Entry, String> staged : pending.entrySet()) {
            ConfigRegistry.Entry entry = staged.getKey();
            String raw = staged.getValue();
            // 暂存后管理员权限可能已被撤销，真正提交前必须再次检查
            if (!mayEdit(entry)) {
                viewer.sendSystemMessage(ConfigItems.line(CorpseLab.MOD_ID + ".menu.operators_only", ChatFormatting.RED));
                continue;
            }

            if (entry.serverSide()) {
                Object value = ConfigRegistry.decode(entry, raw);
                if (value == null) {
                    continue;
                }
                String error = ConfigRegistry.set(entry, value);
                if (error != null) {
                    viewer.sendSystemMessage(ConfigItems.line(CorpseLab.MOD_ID + ".menu.save_failed",
                            ChatFormatting.RED, ConfigItems.settingName(entry)));
                    com.pyrostella.corpselab.CorpseLab.LOGGER.warn("Could not save {}: {}", entry.path(), error);
                }
            } else {
                ClientSettings.set(viewer, entry.path(), raw);
            }
        }
        pending.clear();
        viewer.closeContainer();
    }

    /** 红叉：丢弃全部暂存改动，配置还是打开界面时的样子 */
    private void discard() {
        pending.clear();
        viewer.closeContainer();
    }

    /**
     * 一次点击能把数值挪动多少
     *
     * <p>按范围大小缩放，因为这些值从每秒零点几格一直到二十分钟的超时
     * 单一的步长没法同时伺候两头
     */
    private static double step(ConfigRegistry.Entry entry) {
        double span = entry.max() - entry.min();
        double base = span <= 2.0D ? 0.05D
                : span <= 20.0D ? 0.25D
                : span <= 100.0D ? 1.0D
                : span <= 2000.0D ? 10.0D
                : 50.0D;
        return entry.kind() == ConfigRegistry.Kind.INTEGER ? Math.max(1.0D, Math.rint(base)) : base;
    }

    private void adjust(ConfigRegistry.Entry entry, int direction, boolean coarse) {
        double current;
        try {
            current = Double.parseDouble(rawValue(entry));
        } catch (RuntimeException unreadable) {
            return;
        }

        double moved = current + direction * step(entry) * (coarse ? 10.0D : 1.0D);
        double clamped = Math.max(entry.min(), Math.min(entry.max(), moved));

        if (entry.kind() == ConfigRegistry.Kind.INTEGER) {
            stage(entry, (int) Math.round(clamped));
        } else {
            // 步长都是整齐的数，浮点加法却不是，所以把值重新吸附到三位小数
            // 免得它漂成 0.7500000000000001
            stage(entry, Math.round(clamped * 1000.0D) / 1000.0D);
        }
    }

    // ---- 输入 ------------------------------------------------------------------------

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer clicker) || clicker != viewer) {
            return;
        }
        // 菜单以下全是玩家自己的物品栏，而这个菜单从不搬动物品
        // 所以那些点击直接丢弃
        if (slotId < 0 || slotId >= SIZE) {
            return;
        }

        if (handleNavigation(slotId)) {
            return;
        }
        if (section == null) {
            openSection(slotId);
            return;
        }

        List<ConfigRegistry.Entry> entries = sectionEntries();
        int index = page * PAGE_SIZE + slotId;
        if (index >= entries.size()) {
            return;
        }

        ConfigRegistry.Entry entry = entries.get(index);
        if (!mayEdit(entry)) {
            deny();
            return;
        }
        // 这是值还没上报过的客户端设置：对它动手就等于
        // 猜它现在是什么
        if (rawValue(entry) == null) {
            deny();
            return;
        }

        boolean reset = clickType == ClickType.THROW;
        boolean coarse = clickType == ClickType.QUICK_MOVE;

        if (reset) {
            stage(entry, entry.defaultValue());
        } else if (entry.kind() == ConfigRegistry.Kind.BOOLEAN) {
            stage(entry, !Boolean.parseBoolean(rawValue(entry)));
        } else if (entry.kind() == ConfigRegistry.Kind.ENUM) {
            // 枚举只有几个名字，点一下换下一个，不用另做界面
            stage(entry, ConfigRegistry.next(entry, rawValue(entry)));
        } else if (entry.min() != null) {
            adjust(entry, button == 1 ? -1 : 1, coarse);
        } else {
            // 列表和自由文本由配置文件编辑
            deny();
            return;
        }

        click();
        rebuild();
    }

    private boolean handleNavigation(int slotId) {
        switch (slotId) {
            case SLOT_CONFIRM -> {
                // 有暂存改动才需要提示音，纯关界面不响
                if (!pending.isEmpty()) {
                    click();
                }
                commit();
                return true;
            }
            case SLOT_CANCEL -> {
                if (!pending.isEmpty()) {
                    click();
                }
                discard();
                return true;
            }
            case SLOT_BACK -> {
                if (section != null) {
                    section = null;
                    page = 0;
                    click();
                    rebuild();
                }
                return true;
            }
            case SLOT_PREVIOUS -> {
                if (section != null && page > 0) {
                    page--;
                    click();
                    rebuild();
                }
                return true;
            }
            case SLOT_NEXT -> {
                if (section != null && page < pageCount() - 1) {
                    page++;
                    click();
                    rebuild();
                }
                return true;
            }
            case SLOT_INFO -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void openSection(int slotId) {
        List<String> names = new ArrayList<>(ConfigRegistry.bySection().keySet());
        if (slotId < names.size()) {
            section = names.get(slotId);
            page = 0;
            click();
            rebuild();
        }
    }

    private void click() {
        viewer.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 0.4F, 1.0F);
    }

    private void deny() {
        viewer.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.MASTER, 0.4F, 1.0F);
    }

    // ---- 这里没有一样是真正的容器 ----------------------------------------------------

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player == viewer;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, net.minecraft.world.inventory.Slot slot) {
        return false;
    }
}
