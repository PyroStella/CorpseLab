package com.pyrostella.corpselab.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 扩展顺序和撤销语义必须独立于模组的加载顺序 */
class ExtensionRegistryTest {
    @Test
    void priorityAndIdsDefineOrder() {
        ExtensionRegistry<String> registry = new ExtensionRegistry<>();
        registry.register("mod:z", 0, "fallback");
        registry.register("mod:b", 10, "second");
        registry.register("mod:a", 10, "first");
        assertEquals(List.of("first", "second", "fallback"),
                registry.entries().stream().map(ExtensionRegistry.Entry::value).toList());
    }

    @Test
    void duplicateIdDoesNotReplaceExistingExtension() {
        ExtensionRegistry<String> registry = new ExtensionRegistry<>();
        registry.register("mod:effect", 0, "original");
        assertThrows(IllegalArgumentException.class, () -> registry.register("mod:effect", 10, "new"));
        assertEquals("original", registry.entries().get(0).value());
    }

    @Test
    void oldHandleCannotRemoveReRegisteredId() {
        ExtensionRegistry<String> registry = new ExtensionRegistry<>();
        var first = registry.register("mod:effect", 0, "effect");
        first.close();
        registry.register("mod:effect", 0, "effect");
        first.close();
        assertEquals(1, registry.entries().size());
    }

    @Test
    void snapshotStaysUsableDuringUnregistration() {
        ExtensionRegistry<String> registry = new ExtensionRegistry<>();
        var handle = registry.register("mod:effect", 0, "effect");
        var snapshot = registry.entries();
        handle.close();
        assertEquals(1, snapshot.size());
        assertTrue(registry.entries().isEmpty());
        assertNotSame(snapshot, registry.entries());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test
    void unchangedRegistryKeepsVersionIdentity() {
        ExtensionRegistry<String> registry = new ExtensionRegistry<>();
        var original = registry.entries();
        assertSame(original, registry.entries());
        registry.register("mod:effect", 0, "effect");
        assertNotSame(original, registry.entries());
        assertSame(registry.entries(), registry.entries());
    }
}
