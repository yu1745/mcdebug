package com.mcdebug.loader.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.ItemCapability;

/**
 * Capability lookups for the Kotlin seam implementation.
 *
 * <p>Java interop shim on purpose: the context type parameter of a capability is a platform type,
 * and Kotlin rejects passing {@code null} (the documented "unsided" lookup, and the Void context of
 * item capabilities) into a Java generic parameter without a nullability warning. Declaring the
 * calls here keeps the Kotlin side warning-free.
 */
public final class NeoCapabilities {
    private NeoCapabilities() {
    }

    /** Block capability lookup; {@code side == null} means the unsided lookup. */
    public static <T> T block(BlockCapability<T, Direction> capability, ServerLevel level, BlockPos pos, Direction side) {
        return capability.getCapability(level, pos, level.getBlockState(pos), level.getBlockEntity(pos), side);
    }

    /** Void-context item capability lookup (fluid handler / energy storage hosted by an item). */
    public static <T> T item(ItemCapability<T, Void> capability, ItemStack stack) {
        return capability.getCapability(stack, null);
    }
}
