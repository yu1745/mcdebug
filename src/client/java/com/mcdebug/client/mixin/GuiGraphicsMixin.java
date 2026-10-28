package com.mcdebug.client.mixin;

import com.mcdebug.client.DrawRecorder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draw-log hooks. Observation only: nothing is cancelled or modified, and every
 * hook is one static field check unless a capture armed {@link DrawRecorder}.
 * Version-pinned to 1.21.1's GuiGraphics.
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {
    private Matrix4f mcdebug$pose() {
        return ((GuiGraphics) (Object) this).pose().last().pose();
    }

    @Inject(method = "innerBlit(Lnet/minecraft/resources/ResourceLocation;IIIIIFFFF)V", at = @At("HEAD"))
    private void mcdebug$blit(ResourceLocation texture, int x1, int x2, int y1, int y2, int z,
                              float u0, float u1, float v0, float v1, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.blit(mcdebug$pose(), texture, x1, x2, y1, y2, z, u0, u1, v0, v1, null);
    }

    @Inject(method = "innerBlit(Lnet/minecraft/resources/ResourceLocation;IIIIIFFFFFFFF)V", at = @At("HEAD"))
    private void mcdebug$blitColored(ResourceLocation texture, int x1, int x2, int y1, int y2, int z,
                                     float u0, float u1, float v0, float v1, float r, float g, float b, float a, CallbackInfo ci) {
        if (DrawRecorder.active) {
            DrawRecorder.blit(mcdebug$pose(), texture, x1, x2, y1, y2, z, u0, u1, v0, v1, new float[] {r, g, b, a});
        }
    }

    @Inject(method = "blit(Lnet/minecraft/resources/ResourceLocation;IIIIIIIFFII)V", at = @At("HEAD"))
    private void mcdebug$texelEnter(ResourceLocation texture, int x1, int x2, int y1, int y2, int z, int width, int height,
                                    float u, float v, int textureWidth, int textureHeight, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.enterTexel(u, v, width, height, textureWidth, textureHeight);
    }

    @Inject(method = "blit(Lnet/minecraft/resources/ResourceLocation;IIIIIIIFFII)V", at = @At("RETURN"))
    private void mcdebug$texelExit(CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.exit();
    }

    // Mixin callback handlers take all target arguments or none, so each overload gets its own HEAD hook.
    @Inject(method = "blitSprite(Lnet/minecraft/resources/ResourceLocation;IIIII)V", at = @At("HEAD"))
    private void mcdebug$spriteEnter(ResourceLocation sprite, int x, int y, int z, int width, int height, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.enterSprite(sprite.toString());
    }

    @Inject(method = "blitSprite(Lnet/minecraft/resources/ResourceLocation;IIIIIIIII)V", at = @At("HEAD"))
    private void mcdebug$spriteRegionEnter(ResourceLocation sprite, int textureWidth, int textureHeight, int u, int v,
                                           int x, int y, int z, int width, int height, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.enterSprite(sprite.toString());
    }

    @Inject(method = "blit(IIIIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V", at = @At("HEAD"))
    private void mcdebug$atlasSpriteEnter(int x, int y, int z, int width, int height, TextureAtlasSprite sprite, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.enterSprite(sprite.contents().name().toString());
    }

    @Inject(method = "blit(IIIIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;FFFF)V", at = @At("HEAD"))
    private void mcdebug$tintedAtlasSpriteEnter(int x, int y, int z, int width, int height, TextureAtlasSprite sprite,
                                                float r, float g, float b, float a, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.enterSprite(sprite.contents().name().toString());
    }

    @Inject(method = {
        "blitSprite(Lnet/minecraft/resources/ResourceLocation;IIIII)V",
        "blitSprite(Lnet/minecraft/resources/ResourceLocation;IIIIIIIII)V",
        "blit(IIIIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V",
        "blit(IIIIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;FFFF)V",
    }, at = @At("RETURN"))
    private void mcdebug$spriteExit(CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.exit();
    }

    @Inject(method = "fill(Lnet/minecraft/client/renderer/RenderType;IIIIII)V", at = @At("HEAD"))
    private void mcdebug$fill(RenderType type, int x1, int y1, int x2, int y2, int z, int color, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.fill(mcdebug$pose(), x1, y1, x2, y2, z, color);
    }

    @Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I", at = @At("RETURN"))
    private void mcdebug$string(Font font, String text, int x, int y, int color, boolean shadow, CallbackInfoReturnable<Integer> cir) {
        if (DrawRecorder.active && text != null) DrawRecorder.text(mcdebug$pose(), text, x, y, color, shadow, cir.getReturnValue() - x);
    }

    @Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I", at = @At("RETURN"))
    private void mcdebug$sequence(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow,
                                  CallbackInfoReturnable<Integer> cir) {
        if (DrawRecorder.active) DrawRecorder.text(mcdebug$pose(), text, x, y, color, shadow, cir.getReturnValue() - x);
    }

    @Inject(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;IIII)V",
        at = @At("HEAD"))
    private void mcdebug$item(LivingEntity entity, Level level, ItemStack stack, int x, int y, int seed, int z, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.item(mcdebug$pose(), stack, x, y);
    }

    @Inject(method = "renderTooltipInternal", at = @At("HEAD"))
    private void mcdebug$tooltip(Font font, List<ClientTooltipComponent> components, int x, int y,
                                 ClientTooltipPositioner positioner, CallbackInfo ci) {
        if (!DrawRecorder.active) return;
        List<String> lines = new ArrayList<>();
        for (ClientTooltipComponent component : components) {
            if (component instanceof ClientTextTooltip text) {
                lines.add(DrawRecorder.INSTANCE.plain(((ClientTextTooltipAccessor) text).mcdebug$getText()));
            } else {
                lines.add("<" + component.getClass().getSimpleName() + ">");
            }
        }
        DrawRecorder.tooltip(lines, x, y);
    }
}
