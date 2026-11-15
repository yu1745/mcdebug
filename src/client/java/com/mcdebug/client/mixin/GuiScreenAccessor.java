package com.mcdebug.client.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Replaces a reloaded container Screen without closing its live menu. */
@Mixin(Gui.class)
public interface GuiScreenAccessor {
    @Accessor("screen")
    void mcdebug$setScreen(Screen screen);
}
