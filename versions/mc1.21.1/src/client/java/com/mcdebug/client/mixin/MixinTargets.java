package com.mcdebug.client.mixin;

/**
 * Compile-time mixin targets that differ per loader: the two builds compile the same mixin sources,
 * but each provides its own copy of this interface (Fabric excludes the NeoForge one and vice
 * versa), so annotation values stay compile-time constants.
 *
 * <p>Only values that genuinely differ per loader belong here — everything else is shared.
 */
public interface MixinTargets {
    /**
     * The call inside {@code GameRenderer#render} that draws the open Screen together with its
     * tooltips. Vanilla/Fabric call {@code Screen#renderWithTooltip} directly; NeoForge replaces
     * that with {@code ClientHooks#drawScreen(Screen, GuiGraphics, int, int, float)} (same operand
     * shape: receiver first, then the graphics, the mouse coordinates and the partial tick).
     */
    String SCREEN_DRAW = "Lnet/minecraft/client/gui/screens/Screen;renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V";
}
