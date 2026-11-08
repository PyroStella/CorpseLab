package com.pyrostella.corpselab.internal.registry;

import com.pyrostella.corpselab.api.Registration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 发布不可变快照，遍历期间注册或撤销扩展不会破坏当前调用 */
public final class ExtensionRegistry<T> {
    public record Entry<T>(String id, int priority, T value) {
    }

    private volatile List<Entry<T>> entries = List.of();

    /** 优先级越大越靠前，同级按 ID 排序，避免模组加载顺序影响行为 */
    public synchronized Registration register(String id, int priority, T value) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(value);
        if (id.isBlank() || this.entries.stream().anyMatch(entry -> entry.id().equals(id))) {
            throw new IllegalArgumentException("扩展 ID 为空或重复：" + id);
        }
        Entry<T> entry = new Entry<>(id, priority, value);
        List<Entry<T>> updated = new ArrayList<>(this.entries);
        updated.add(entry);
        updated.sort(Comparator.<Entry<T>>comparingInt(Entry::priority).reversed().thenComparing(Entry::id));
        this.entries = List.copyOf(updated);
        return () -> this.remove(entry);
    }

    private synchronized void remove(Entry<T> entry) {
        List<Entry<T>> updated = new ArrayList<>(this.entries);
        // 旧句柄不能误删后来使用相同 ID 注册的新实现
        if (updated.removeIf(candidate -> candidate == entry)) {
            this.entries = List.copyOf(updated);
        }
    }

    public List<Entry<T>> entries() {
        return this.entries;
    }
}
