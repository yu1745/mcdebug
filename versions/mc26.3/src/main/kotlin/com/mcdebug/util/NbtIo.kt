package com.mcdebug.util

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.level.storage.ValueInput

/** Bridges the RPC's NBT representation to Minecraft 26.3's registry-aware value IO. */
object NbtIo {
    fun input(registries: HolderLookup.Provider, tag: CompoundTag): ValueInput =
        TagValueInput.create(ProblemReporter.DISCARDING, registries, tag)

    fun save(entity: Entity): CompoundTag {
        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess())
        entity.saveWithoutId(output)
        return output.buildResult()
    }
}
