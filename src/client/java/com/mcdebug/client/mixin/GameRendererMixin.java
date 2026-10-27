package com.mcdebug.client.mixin;

import com.mcdebug.client.RenderControl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isWindowActive()Z"))
    private boolean mcdebug$backgroundFocus(Minecraft client) {
        // Only suppress automatic lost-focus pausing, not explicit pause screens.
        return RenderControl.keepRunningUnfocused() || client.isWindowActive();
    }

    @ModifyArg(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/Screen;renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"), index = 1)
    private int mcdebug$mouseX(int original) {
        return RenderControl.mouseX(original);
    }

    @ModifyArg(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/Screen;renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"), index = 2)
    private int mcdebug$mouseY(int original) {
        return RenderControl.mouseY(original);
    }
}
