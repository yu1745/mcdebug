package com.mcdebug.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mcdebug.client.RenderControl;
import com.mcdebug.client.ScreenGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    // WrapOperation, not Redirect: remapJar writes `at` as an array, which Redirect rejects.
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isWindowActive()Z"))
    private boolean mcdebug$backgroundFocus(Minecraft client, Operation<Boolean> original) {
        // Only suppress automatic lost-focus pausing, not explicit pause screens.
        return RenderControl.keepRunningUnfocused() || original.call(client);
    }

    /**
     * One wrapper for the Screen draw: the lite-mode virtual cursor replaces the
     * mouse arguments, and {@link ScreenGuard} turns a render exception of the
     * open Screen into a recorded fault instead of a crash (unless disabled).
     */
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/Screen;renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
    private void mcdebug$renderScreen(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                      Operation<Void> original) {
        if (ScreenGuard.shouldSkip(screen)) return;
        try {
            original.call(screen, graphics, RenderControl.mouseX(mouseX), RenderControl.mouseY(mouseY), partialTick);
        } catch (Throwable error) {
            if (!ScreenGuard.capture(screen, error)) ScreenGuard.rethrow(error);
        }
    }
}
