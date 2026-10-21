package com.mcdebug.api

import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mojang.authlib.GameProfile
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import net.minecraftforge.common.util.FakePlayer
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Fake player used by the interaction RPCs (world.placeAsPlayer, world.useOnBlock, world.attackBlock,
 * world.breakBlock, world.useItem, world.useItemHold, world.interactEntity, world.attackEntity, screen.*).
 *
 * Forge port: instead of the Fabric mod's hand-spawned ServerPlayerEntity with a custom no-op network
 * handler, this uses Forge's own [FakePlayer] (a ServerPlayer subclass whose network handler drops all
 * packets, invulnerable, tick() disabled). Mods already special-case `instanceof FakePlayer`, so this is
 * the realistic identity for automation. The player is NOT added to the world's player list (it is not
 * ticked and is invisible to world.players()/@a), which differs from the Fabric mod; per-call state
 * (position, hands, sneaking, game mode) is set and reset under a global lock exactly like Fabric.
 */
internal object FakePlayerPool {
    // Fixed UUID — same identity as the Fabric mod's fake player.
    val PROFILE: GameProfile = GameProfile(
        UUID.fromString("8c0a4d6e-3f2b-4a1e-9d8c-5e7f0a1b2c3d"),
        "[mcdebug_fake_player]"
    )

    private val cache = HashMap<Pair<MinecraftServer, ServerLevel>, ServerPlayer>()

    /** Serializes all fake-player interactions (state is mutated per call). Reentrant. */
    private val interactionLock = ReentrantLock()

    fun get(server: MinecraftServer, world: ServerLevel): ServerPlayer {
        val key = server to world
        cache[key]?.let { if (!it.isRemoved) return it }
        val p = create(world, PROFILE)
        cache[key] = p
        return p
    }

    fun create(world: ServerLevel, profile: GameProfile): ServerPlayer {
        val p = FakePlayer(world, profile)
        p.abilities.instabuild = false
        p.abilities.mayBuild = true
        p.abilities.flying = false
        p.abilities.mayfly = false
        ensureProtected(p)
        return p
    }

    fun <T> withFakePlayer(
        server: MinecraftServer,
        world: ServerLevel,
        gamemode: GameType = GameType.SURVIVAL,
        block: (ServerPlayer) -> T,
    ): T = interactionLock.withLock {
        val player = get(server, world)
        val oldAbilities = AbilitiesSnapshot.capture(player)
        val oldGameMode = player.gameMode.gameModeForPlayer
        try {
            applyGameMode(player, gamemode)
            block(player)
        } finally {
            oldAbilities.restore(player)
            player.setGameMode(oldGameMode)
            ensureProtected(player)
        }
    }

    /** Parse the optional `gamemode` param. Default: survival. Override: "creative". */
    fun gameModeFrom(p: JsonObject?): GameType {
        val s = p?.getStringOrNull("gamemode") ?: return GameType.SURVIVAL
        return when (s.lowercase()) {
            "survival" -> GameType.SURVIVAL
            "creative" -> GameType.CREATIVE
            else -> throw RpcException(
                RpcErrors.INVALID_PARAMS,
                "invalid gamemode: $s (allowed: survival, creative)"
            )
        }
    }

    fun applyGameMode(player: ServerPlayer, mode: GameType) {
        player.setGameMode(mode)
        player.abilities.mayBuild = true
        ensureProtected(player)
    }

    fun ensureProtected(player: ServerPlayer) {
        player.isInvulnerable = true
        player.abilities.invulnerable = true
        player.setNoGravity(true)
        player.setDeltaMovement(0.0, 0.0, 0.0)
        player.fallDistance = 0f
        player.setOnGround(true)
    }

    /** Drop a per-session player (ScreenOps teardown). */
    fun discard(player: ServerPlayer) {
        cache.entries.removeIf { (_, p) -> p === player }
    }

    /** Kept for API parity with the Fabric mod (there the keepalive tick is registered here). */
    fun install() {}

    fun shutdown() {
        cache.clear()
    }

    private data class AbilitiesSnapshot(
        val invulnerable: Boolean,
        val flying: Boolean,
        val allowFlying: Boolean,
        val creativeMode: Boolean,
        val allowModifyWorld: Boolean,
        val flySpeed: Float,
        val walkSpeed: Float
    ) {
        fun restore(player: ServerPlayer) {
            player.abilities.invulnerable = invulnerable
            player.abilities.flying = flying
            player.abilities.mayfly = allowFlying
            player.abilities.instabuild = creativeMode
            player.abilities.mayBuild = allowModifyWorld
            player.abilities.setFlyingSpeed(flySpeed)
            player.abilities.setWalkingSpeed(walkSpeed)
        }

        companion object {
            fun capture(player: ServerPlayer): AbilitiesSnapshot = AbilitiesSnapshot(
                invulnerable = player.abilities.invulnerable,
                flying = player.abilities.flying,
                allowFlying = player.abilities.mayfly,
                creativeMode = player.abilities.instabuild,
                allowModifyWorld = player.abilities.mayBuild,
                flySpeed = player.abilities.flyingSpeed,
                walkSpeed = player.abilities.walkingSpeed
            )
        }
    }
}
