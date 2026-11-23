package com.mcdebug.client.mixin;

/**
 * NeoForge copy of the per-loader mixin targets (see the Fabric copy in {@code src/client/java}):
 * NeoForge rewrites the Screen draw inside {@code GameRenderer#render} into a hook call, and the
 * operand list is the same shape, so the shared {@code GameRendererMixin} handler still applies.
 */
public interface MixinTargets {
    String SCREEN_DRAW = "Lnet/neoforged/neoforge/client/ClientHooks;drawScreen(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/gui/GuiGraphics;IIF)V";
}
