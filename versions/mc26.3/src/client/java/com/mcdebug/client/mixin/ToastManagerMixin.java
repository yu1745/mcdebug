package com.mcdebug.client.mixin;

import com.mcdebug.client.ClientToasts;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 的提示队列是 {@code ToastManager}（1.21.1 为 {@code ToastComponent}）：
 * {@code addToast(Toast)} 只入队，在 HEAD 拦截即可同时挡住已排队与之后新增的弹窗。
 */
@Mixin(ToastManager.class)
public abstract class ToastManagerMixin {
    @Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
    private void mcdebug$filterToast(Toast toast, CallbackInfo ci) {
        if (!ClientToasts.getEnabled()) ci.cancel();
    }
}
