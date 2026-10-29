package com.mcdebug.client.mixin;

import com.mcdebug.client.WidgetFrames;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observation only: stamps each widget with the frame that drew it, so gui.screen can
 * tell scrolled-out list entries (whose x/y are only refreshed while drawn) from live ones.
 */
@Mixin(AbstractWidget.class)
public abstract class AbstractWidgetRenderMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void mcdebug$stampFrame(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        WidgetFrames.mark((AbstractWidget) (Object) this);
    }
}
