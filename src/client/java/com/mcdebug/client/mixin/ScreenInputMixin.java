package com.mcdebug.client.mixin;

import com.mcdebug.client.input.GuiInputModifiers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Overrides only a synthetic event's synchronous call stack, never the desktop keyboard. */
@Mixin(Minecraft.class)
public abstract class ScreenInputMixin {
    @Inject(method = "hasControlDown()Z", at = @At("HEAD"), cancellable = true)
    private void mcdebug$controlDown(CallbackInfoReturnable<Boolean> cir) {
        Boolean value = GuiInputModifiers.controlDown(false);
        if (value != null) cir.setReturnValue(value);
    }

    @Inject(method = "hasShiftDown()Z", at = @At("HEAD"), cancellable = true)
    private void mcdebug$shiftDown(CallbackInfoReturnable<Boolean> cir) {
        Boolean value = GuiInputModifiers.shiftDown();
        if (value != null) cir.setReturnValue(value);
    }

    @Inject(method = "hasAltDown()Z", at = @At("HEAD"), cancellable = true)
    private void mcdebug$altDown(CallbackInfoReturnable<Boolean> cir) {
        Boolean value = GuiInputModifiers.altDown();
        if (value != null) cir.setReturnValue(value);
    }
}
