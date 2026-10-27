package com.mcdebug.client.mixin;

import com.mcdebug.client.RenderControl;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Version-pinned interception: never cancel runTick, its tasks, ticks or event pump. */
@Mixin(Minecraft.class)
public abstract class MinecraftRenderMixin {
    @Redirect(method = "runTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void mcdebug$renderFrame(GameRenderer renderer, DeltaTracker delta, boolean renderWorld) {
        RenderControl.renderFrame(renderer, delta, renderWorld);
    }

    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void mcdebug$loopLimit(CallbackInfoReturnable<Integer> result) {
        result.setReturnValue(RenderControl.frameLimit(result.getReturnValue()));
    }
}
