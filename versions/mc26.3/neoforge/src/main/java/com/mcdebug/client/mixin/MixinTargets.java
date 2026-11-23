package com.mcdebug.client.mixin;

/** The NeoForge item-tooltip overload carries an extra host stack. */
public interface MixinTargets {
    String TOOLTIP_RENDER = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;ZLnet/minecraft/world/item/ItemStack;)V";
}
