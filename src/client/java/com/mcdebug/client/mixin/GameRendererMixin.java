package com.mcdebug.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mcdebug.client.render.RenderControl;
import com.mcdebug.client.devloop.ScreenGuard;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;

/** Both loaders enter Screen's final extraction method, including NeoForge's GUI layer hook. */
@Mixin(Screen.class)
public abstract class GameRendererMixin {
    @WrapMethod(method = "extractRenderStateWithTooltipAndSubtitles")
    private void mcdebug$extractScreen(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
                                      Operation<Void> original) {
        Screen screen = (Screen)(Object)this;
        if (ScreenGuard.shouldSkip(screen)) return;
        try {
            original.call(graphics, RenderControl.mouseX(mouseX), RenderControl.mouseY(mouseY), partialTick);
        } catch (Throwable error) {
            if (!ScreenGuard.capture(screen, error)) ScreenGuard.rethrow(error);
        }
    }
}
