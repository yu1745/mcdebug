package com.mcdebug.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.io.IOException;
import java.nio.channels.WritableByteChannel;

/** The native pixel pointer, so a capture can glReadPixels only its crop rectangle. */
@Mixin(NativeImage.class)
public interface NativeImageAccessor {
    @Accessor("pixels")
    long mcdebug$getPixels();

    @Invoker("writeToChannel")
    boolean mcdebug$writeToChannel(WritableByteChannel channel) throws IOException;
}
