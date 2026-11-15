package com.mcdebug.client.mixin;

import com.mcdebug.client.render.DrawRecorder;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers both direct GUI text and the new ActiveTextCollector path used by widgets. */
@Mixin(GuiTextRenderState.class)
public abstract class GuiTextRenderStateMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void mcdebug$text(Font font, FormattedCharSequence text, Matrix3x2fc pose, int x, int y,
        int color, int backgroundColor, boolean shadow, boolean includeEmpty, ScreenRectangle scissor, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.text(DrawRecorder.pose(pose), text, x, y, color, shadow, font.width(text));
    }
}
