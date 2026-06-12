package com.mcdebug.api

import com.mojang.authlib.GameProfile
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import java.util.UUID

/**
 * Stable fake ServerPlayerEntity used as the placer for world.placeAsPlayer.
 *
 * Why a real ServerPlayerEntity (not null):
 *   - The vanilla BlockItem.place pipeline calls onPlaced(world, pos, state, placer, stack)
 *     and Criteria.PLACED_BLOCK.trigger(placer, ...). With a null placer, mod code that
 *     branches on context.getPlayer() != null sees the wrong branch and stats/advancements
 *     are never credited.
 *   - The "stable" requirement is just a stable UUID. The same GameProfile is reused
 *     across all calls, so any mod that records "placed by" sees a consistent identity
 *     instead of a fresh null every time.
 *
 * One instance is kept per (server, world) pair:
 *   - ServerPlayerEntity's constructor calls moveToSpawn(world) which does an O(N) spawn
 *     search; caching amortizes this to a one-time cost per (server, world).
 *   - We never call world.spawnEntity on the player, so it never enters the world's
 *     entity list and is never ticked.
 *   - Per call we set its position to the placement pos (for sound falloff) and
 *     yaw/pitch from the playerFacing param. The cached instance is mutated, not cloned.
 *
 * Limitations vs a real player:
 *   - Advancement triggers fire on the (per-server) advancement tracker, but since the
 *     player is never registered with the player manager, no real player sees the
 *     criterion. Same goes for stats: the stat handler exists but isn't connected to
 *     any saved data. This is acceptable for placement simulation.
 *   - The player's inventory is empty; getStackInHand returns ItemStack.EMPTY. Vanilla
 *     BlockItem.place only uses the stack from the ItemPlacementContext, not the
 *     placer's held item, so this is fine.
 */
internal object FakePlayerPool {
    // Fixed UUID — same identity across server restarts, across calls, across dims.
    // mcdebug_<random> namespace makes it easy to grep in NBT.
    val PROFILE: GameProfile = GameProfile(
        UUID.fromString("8c0a4d6e-3f2b-4a1e-9d8c-5e7f0a1b2c3d"),
        "[mcdebug_fake_player]"
    )

    private val cache = HashMap<Pair<MinecraftServer, ServerWorld>, ServerPlayerEntity>()

    fun get(server: MinecraftServer, world: ServerWorld): ServerPlayerEntity {
        return cache.getOrPut(server to world) {
            val p = ServerPlayerEntity(server, world, PROFILE)
            // creative + invulnerable so:
            //   - the placed stack isn't decremented (we made a fresh stack anyway, but
            //     this keeps the stack reusable if we ever cache it)
            //   - sound / GameEvent / onPlaced see a "creative" placer, matching the
            //     expected automation use case (no survival-mode interceptions)
            p.abilities.creativeMode = true
            p.abilities.invulnerable = true
            p.abilities.flying = true
            p.setInvulnerable(true)
            p
        }
    }
}
