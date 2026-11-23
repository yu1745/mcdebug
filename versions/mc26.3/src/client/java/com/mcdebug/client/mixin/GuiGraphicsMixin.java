package com.mcdebug.client.mixin;

import com.mcdebug.client.render.DrawRecorder;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import com.mcdebug.client.render.RenderControl;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes Minecraft 26.3's GUI state extraction without changing its drawing. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsMixin {
    private Matrix4f mcdebug$pose() {
        return DrawRecorder.pose(((GuiGraphicsExtractor)(Object)this).pose());
    }

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static int mcdebug$mouseX(int original) { return RenderControl.mouseX(original); }

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private static int mcdebug$mouseY(int original) { return RenderControl.mouseY(original); }

    @Inject(method = "innerBlit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;IIIIFFFFI)V", at = @At("HEAD"))
    private void mcdebug$blit(RenderPipeline pipeline, GpuTextureView texture, GpuSampler sampler,
        int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1, int color, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.blit(mcdebug$pose(), texture.texture().getLabel(), x0, x1, y0, y1, 0,
            u0, u1, v0, v1, new float[]{((color >>> 16) & 255) / 255f, ((color >>> 8) & 255) / 255f,
                (color & 255) / 255f, ((color >>> 24) & 255) / 255f});
    }

    @Inject(method = "blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V", at = @At("HEAD"))
    private void mcdebug$texelEnter(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v,
        int width, int height, int srcWidth, int srcHeight, int texWidth, int texHeight, int color, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.enterTexel(u, v, srcWidth, srcHeight, texWidth, texHeight);
    }
    @Inject(method = "blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V", at = @At("RETURN"))
    private void mcdebug$texelExit(CallbackInfo ci) { if (DrawRecorder.active) DrawRecorder.exit(); }

    @Inject(method = "blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V", at = @At("HEAD"))
    private void mcdebug$sprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height, int color, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.enterSprite(sprite.toString());
    }
    @Inject(method = "blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIIII)V", at = @At("HEAD"))
    private void mcdebug$spriteRegion(RenderPipeline pipeline, Identifier sprite, int sw, int sh, int u, int v, int x, int y, int w, int h, int color, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.enterSprite(sprite.toString());
    }
    @Inject(method = {"blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V", "blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIIII)V"}, at = @At("RETURN"))
    private void mcdebug$spriteExit(CallbackInfo ci) { if (DrawRecorder.active) DrawRecorder.exit(); }

    @Inject(method = "fill(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;IIIII)V", at = @At("HEAD"))
    private void mcdebug$fill(RenderPipeline pipeline, int x0, int y0, int x1, int y1, int color, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.fill(mcdebug$pose(), x0, y0, x1, y1, 0, color);
    }

    @Inject(method = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"))
    private void mcdebug$item(LivingEntity owner, Level level, ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.item(mcdebug$pose(), stack, x, y);
    }

    @Inject(method = "innerTiledBlit", at = @At("HEAD"))
    private void mcdebug$tiledBlit(RenderPipeline pipeline, GpuTextureView texture, GpuSampler sampler,
        int tileWidth, int tileHeight, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1,
        int color, CallbackInfo ci) {
        if (DrawRecorder.active) DrawRecorder.blit(mcdebug$pose(), texture.texture().getLabel(), x0, x1, y0, y1, 0,
            u0, u1, v0, v1, null);
    }

    // NeoForge routes item tooltips through an extra overload. Both loaders reach this draw
    // after their cancellation hooks; capture the shared arguments instead of pinning one overload.
    @Inject(method = MixinTargets.TOOLTIP_RENDER, at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/gui/screens/inventory/tooltip/TooltipRenderUtil;extractTooltipBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIILnet/minecraft/resources/Identifier;)V"))
    private void mcdebug$tooltip(CallbackInfo ci, @Local(argsOnly = true) List<ClientTooltipComponent> components,
        @Local(argsOnly = true, ordinal = 0) int x, @Local(argsOnly = true, ordinal = 1) int y) {
        if (!DrawRecorder.active) return;
        List<String> lines = new ArrayList<>();
        for (ClientTooltipComponent component : components) {
            if (component instanceof ClientTextTooltip text) lines.add(DrawRecorder.INSTANCE.plain(((ClientTextTooltipAccessor)text).mcdebug$getText()));
            else lines.add("<" + component.getClass().getSimpleName() + ">");
        }
        DrawRecorder.tooltip(lines, x, y);
    }
}
