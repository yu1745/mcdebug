package com.mcdebug.client.mixin;

import com.mcdebug.client.RenderControl;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a lite mode throttles the loop, the frame-limit wait can be woken by an incoming RPC. */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    @Inject(method = "limitDisplayFPS", at = @At("HEAD"), cancellable = true)
    private static void mcdebug$wakeableLimit(int fps, CallbackInfo ci) {
        if (RenderControl.limitDisplayFps(fps)) ci.cancel();
    }
}
