package com.pyrostella.corpselab.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class CorpseLabKeybinds {

    public static final String CATEGORY = "key.categories.corpselab";

    /** 按住即可抓住附近的一具身体并拖拽 */
    public static final KeyMapping GRAB = new KeyMapping(
            "key.corpselab.grab",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY);

    private CorpseLabKeybinds() {
    }
}
