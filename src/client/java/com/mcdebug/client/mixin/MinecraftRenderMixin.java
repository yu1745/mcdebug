package com.mcdebug.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mcdebug.client.render.RenderControl;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Gate extraction and GPU rendering, leaving natural ticks and the window event pump intact. */
@Mixin(Minecraft.class)
public abstract class MinecraftRenderMixin {
    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void mcdebug$extractFrame(GameRenderer renderer, DeltaTracker delta, boolean advanceGameTime, Operation<Void> original) {
        RenderControl.extractFrame(renderer, delta, advanceGameTime);
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
    private void mcdebug$renderFrame(GameRenderer renderer, Operation<Void> original) {
        RenderControl.renderFrame(renderer);
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/platform/FramerateLimitTracker;getFramerateLimit()I"))
    private int mcdebug$loopLimit(FramerateLimitTracker tracker, Operation<Integer> original) {
        return RenderControl.frameLimit(original.call(tracker));
    }

    @WrapOperation(method = "pauseIfInactive", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/platform/Window;isFocused()Z"))
    private boolean mcdebug$backgroundFocus(Window window, Operation<Boolean> original) {
        return RenderControl.keepRunningUnfocused() || original.call(window);
    }
}
