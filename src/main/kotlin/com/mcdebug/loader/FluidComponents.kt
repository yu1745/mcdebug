package com.mcdebug.loader

import com.google.gson.JsonElement
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.util.NbtJson
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.nbt.NbtOps

/**
 * Shared encoder for a fluid's component patch (1.21 carries fluid data as components, not NBT),
 * so Fabric (`FluidVariant.components`) and NeoForge (`FluidStack.componentsPatch`) report the
 * exact same `nbt` field in `storage.*` / `fluid.*` DTOs.
 */
internal fun fluidComponentsToJson(changes: DataComponentPatch): JsonElement =
    try {
        NbtJson.toJson(
            DataComponentPatch.CODEC.encodeStart(NbtOps.INSTANCE, changes).result()
                .orElseThrow { RpcException(RpcErrors.INTERNAL_ERROR, "failed to encode fluid components") }
        )
    } catch (e: RpcException) {
        throw e
    } catch (e: Exception) {
        throw RpcException(RpcErrors.INTERNAL_ERROR, "failed to encode fluid components: ${e.message}")
    }
