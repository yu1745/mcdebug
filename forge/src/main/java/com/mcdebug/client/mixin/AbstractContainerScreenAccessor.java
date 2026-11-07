package com.mcdebug.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the GUI rectangle of a container screen so the client-plane RPC agent
 * can crop screenshots to the machine GUI (and map PNG pixels back to GUI
 * coordinates / widget hit tests).
 *
 * <p>All these fields are {@code protected} in vanilla, and there is no public
 * getter for them. {@code getMenu()} is already public, so the menu itself
 * needs no accessor.
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("leftPos")
    int getLeftPos();

    @Accessor("topPos")
    int getTopPos();

    @Accessor("imageWidth")
    int getImageWidth();

    @Accessor("imageHeight")
    int getImageHeight();

    /** The slot under the (possibly virtual) cursor in the last rendered frame, or null. */
    @Accessor("hoveredSlot")
    Slot getHoveredSlot();
}
