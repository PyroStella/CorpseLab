package com.pyrostella.corpselab.internal.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模组所有设置，通过遍历配置 spec 自动发现；菜单由此读取，新设置会自动出现。
 * 名称与说明在语言文件中维护。
 */
public final class ConfigRegistry {

    /** 设置类型，决定用什么控件、如何解析传输值 */
    public enum Kind {
        BOOLEAN,
        INTEGER,
        DECIMAL,
        STRING_LIST,
        STRING,
        ENUM        // 固定几个名字里选，界面点击循环切换
    }

    /** 一项配置的元数据，菜单据此选择控件 */
    public record Entry(String path, String section, String name, Kind kind, String comment, Object defaultValue, Double min, Double max, boolean serverSide) {}

    private static List<Entry> entries;

    private ConfigRegistry() {}

    public static synchronized List<Entry> entries() {
        if (entries == null) {
            List<Entry> found = new ArrayList<>();
            collect(CorpseLabConfig.SERVER_SPEC, true, found);
            collect(CorpseLabConfig.CLIENT_SPEC, false, found);
            entries = List.copyOf(found);
        }
        return entries;
    }

    public static Entry find(String path) {
        for (Entry entry : entries()) {
            if (entry.path().equalsIgnoreCase(path)) {
                return entry;
            }
        }
        return null;
    }

    /** 按顶层分区分组，保持声明顺序 */
    public static Map<String, List<Entry>> bySection() {
        Map<String, List<Entry>> grouped = new LinkedHashMap<>();
        for (Entry entry : entries()) {
            grouped.computeIfAbsent(entry.section(), key -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    private static void collect(ForgeConfigSpec spec, boolean serverSide, List<Entry> out) {
        walk(spec.getSpec(), "", spec, serverSide, out);
    }

    private static void walk(UnmodifiableConfig config, String prefix,
                             ForgeConfigSpec spec, boolean serverSide, List<Entry> out) {
        for (Map.Entry<String, Object> child : config.valueMap().entrySet()) {
            String path = prefix.isEmpty() ? child.getKey() : prefix + "." + child.getKey();
            Object value = child.getValue();

            if (value instanceof UnmodifiableConfig nested) {
                walk(nested, path, spec, serverSide, out);
                continue;
            }
            if (value instanceof ForgeConfigSpec.ValueSpec valueSpec) {
                out.add(describe(path, valueSpec, serverSide));
            }
        }
    }

    private static Entry describe(String path, ForgeConfigSpec.ValueSpec spec, boolean serverSide) {
        int dot = path.lastIndexOf('.');
        String section = dot < 0 ? "general" : path.substring(0, path.indexOf('.'));
        String name = dot < 0 ? path : path.substring(dot + 1);

        Object fallback = spec.getDefault();
        Kind kind = kindOf(fallback);

        Double min = null;
        Double max = null;
        ForgeConfigSpec.Range<?> range = spec.getRange();
        if (range != null && range.getMin() instanceof Number low && range.getMax() instanceof Number high) {
            min = low.doubleValue();
            max = high.doubleValue();
        }

        return new Entry(path, section, name, kind, spec.getComment(), fallback, min, max, serverSide);
    }

    private static Kind kindOf(Object value) {
        if (value instanceof Boolean) {
            return Kind.BOOLEAN;
        }
        if (value instanceof Integer || value instanceof Long) {
            return Kind.INTEGER;
        }
        if (value instanceof Double || value instanceof Float) {
            return Kind.DECIMAL;
        }
        if (value instanceof Enum<?>) {
            return Kind.ENUM;
        }
        if (value instanceof List<?>) {
            return Kind.STRING_LIST;
        }
        return Kind.STRING;
    }

    /** 配置文件是否已加载；主菜单里服务端设置尚未加载，此时读取会抛异常 */
    public static boolean isLoaded(Entry entry) {
        return (entry.serverSide() ? CorpseLabConfig.SERVER_SPEC : CorpseLabConfig.CLIENT_SPEC).isLoaded();
    }

    /** 从运行中的配置读取当前值 */
    @SuppressWarnings("unchecked")
    public static Object get(Entry entry) {
        ForgeConfigSpec spec = entry.serverSide() ? CorpseLabConfig.SERVER_SPEC : CorpseLabConfig.CLIENT_SPEC;
        UnmodifiableConfig values = spec.getValues();

        Object holder = values.get(entry.path());
        if (holder instanceof ForgeConfigSpec.ConfigValue<?> configValue) {
            return configValue.get();
        }
        return holder;
    }

    /** 写入并持久化；成功返回 null，否则返回错误消息 */
    @SuppressWarnings("unchecked")
    public static String set(Entry entry, Object value) {
        ForgeConfigSpec spec = entry.serverSide() ? CorpseLabConfig.SERVER_SPEC : CorpseLabConfig.CLIENT_SPEC;

        if (entry.min() != null && value instanceof Number number) {
            double d = number.doubleValue();
            if (d < entry.min() || d > entry.max()) {
                return "must be between " + trim(entry.min()) + " and " + trim(entry.max());
            }
        }

        if (entry.kind() == Kind.ENUM) {
            value = parseEnum(entry, String.valueOf(value));
            if (value == null) {
                return "must be one of " + String.join(", ", options(entry));
            }
        }

        Object holder = spec.getValues().get(entry.path());
        if (!(holder instanceof ForgeConfigSpec.ConfigValue<?> configValue)) {
            return "no such setting";
        }

        try {
            ((ForgeConfigSpec.ConfigValue<Object>) configValue).set(value);
            spec.save();
            return null;
        } catch (RuntimeException failure) {
            return failure.getMessage() == null ? "rejected" : failure.getMessage();
        }
    }

    /** 精确的传输形式，可原样往返 */
    public static String encode(Object value) {
        if (value instanceof List<?> list) {
            return String.join(",", list.stream().map(String::valueOf).toList());
        }
        return String.valueOf(value);
    }

    /** 解析传输值；失败返回 null */
    public static Object decode(Entry entry, String raw) {
        try {
            return switch (entry.kind()) {
                case BOOLEAN -> Boolean.parseBoolean(raw);
                case INTEGER -> (int) Math.round(Double.parseDouble(raw));
                case DECIMAL -> Double.parseDouble(raw);
                case STRING_LIST -> raw.isBlank() ? List.of() : List.of(raw.split("\\s*,\\s*"));
                case STRING -> raw;
                case ENUM -> parseEnum(entry, raw);
            };
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** 枚举的全部可选名字 */
    public static List<String> options(Entry entry) {
        if (!(entry.defaultValue() instanceof Enum<?> constant)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object option : constant.getDeclaringClass().getEnumConstants()) {
            if (option instanceof Enum<?> named) {
                names.add(named.name());
            }
        }
        return names;
    }

    /** 按名字找枚举常量，忽略大小写；找不到返回 null */
    public static Object parseEnum(Entry entry, String raw) {
        if (raw == null || !(entry.defaultValue() instanceof Enum<?> constant)) {
            return null;
        }
        String wanted = raw.trim();
        for (Object option : constant.getDeclaringClass().getEnumConstants()) {
            if (option instanceof Enum<?> named && named.name().equalsIgnoreCase(wanted)) {
                return named;
            }
        }
        return null;
    }

    /** 循环到下一个枚举值，供界面点击切换 */
    public static String next(Entry entry, String current) {
        List<String> options = options(entry);
        if (options.isEmpty()) {
            return current;
        }
        return options.get((options.indexOf(current) + 1) % options.size());
    }

    /** 格式化用于显示，整数不带 .0 */
    public static String display(Object value) {
        if (value instanceof Double d) {
            return trim(d);
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "(empty)" : String.join(", ",
                    list.stream().map(String::valueOf).toList());
        }
        return String.valueOf(value);
    }

    private static String trim(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1.0e9D) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}