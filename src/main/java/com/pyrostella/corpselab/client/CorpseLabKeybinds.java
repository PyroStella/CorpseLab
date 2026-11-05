package com.pyrostella.corpselab.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class CorpseLabKeybinds {

    public static final String CATEGORY = "key.categories.tumble";

    /** 按住即可抓住附近的一具身体并拖拽 */
    public static final KeyMapping GRAB = new KeyMapping(
            "key.tumble.grab",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY);

    private CorpseLabKeybinds() {
    }
}
