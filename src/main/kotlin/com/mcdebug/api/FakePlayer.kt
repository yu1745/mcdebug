package com.mcdebug.api

import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mojang.authlib.GameProfile
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.Entity
import net.minecraft.network.ClientConnection
import net.minecraft.network.NetworkSide
import net.minecraft.network.packet.Packet
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayNetworkHandler
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.world.GameMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Fake ServerPlayerEntity used by the interaction RPCs (world.placeAsPlayer,
 * world.useOnBlock, world.attackBlock, world.breakBlock, world.useItem,
 * world.useItemHold, world.interactEntity, world.attackEntity, ...).
 *
 * The fake player is SPAWNED into the world: it is created through the same path
 * real players use (`ServerWorld.onPlayerConnected`), so it is registered in the
 * world's entity manager and ticked every server tick. Consequences:
 *   - `world.getPlayers()`, `getEntitiesByClass(ServerPlayerEntity...)`, mob
 *     targeting and per-player tick hooks all see it — mods that initialize
 *     per-player state in join/tick hooks no longer crash on null state.
 *   - It is NOT registered in PlayerManager: invisible to /list, @a, tab list
 *     and permission plugins, and no packets are ever sent to a real client
 *     (the network handler is a no-op that drops all outbound packets, so
 *     openHandledScreen / sendMessageToClient / Criteria.trigger() don't NPE).
 *   - Its presence in `world.players` keeps the world ticking even with no real
 *     players online (desirable for a debug tool).
 *
 * Because it is spawned, the player must never die or drift: it is kept
 * invulnerable, gravity-less and velocity-reset every tick (see
 * [ensureProtected] and the keepalive registered by [install]). The resting
 * game mode is SURVIVAL; every RPC applies its own mode via the optional
 * `gamemode` param (default survival) and restores afterwards.
 *
 * One instance is kept per (server, world) pair, plus one per screen session
 * (ScreenOps). All interaction state (position, yaw/pitch, hand stacks) is set
 * per call and cleaned up afterwards, so calls are isolated.
 */
internal object FakePlayerPool {
    private val LOGGER: Logger = LoggerFactory.getLogger("mcdebug-fakeplayer")

    // Fixed UUID — same identity across server restarts, across calls, across dims.
    // mcdebug_<random> namespace makes it easy to grep in NBT.
    val PROFILE: GameProfile = GameProfile(
        UUID.fromString("8c0a4d6e-3f2b-4a1e-9d8c-5e7f0a1b2c3d"),
        "[mcdebug_fake_player]"
    )

    private val cache = HashMap<Pair<MinecraftServer, ServerWorld>, ServerPlayerEntity>()

    /** Every player created through this pool (cached + per-screen-session), for keepalive/pruning. */
    private val allSpawned = HashSet<ServerPlayerEntity>()

    /**
     * Single global lock serializing all fake-player interactions.
     *
     * Why a lock is needed: the cached ServerPlayerEntity is mutated per call
     * (position, yaw/pitch, hand stack, sneaking, game mode, abilities). The RPC
     * server dispatches each connection on its own thread inside
     * `RpcContext.onServer { ... }` (on the MC main thread, but multiple handler
     * futures can be queued and interleaved). Without serialization, handler A's
     * state could be overwritten by handler B before A reads it back.
     *
     * Deadlock safety: every `withFakePlayer` block runs on the MC main thread
     * and contains only synchronous vanilla calls — no `server.execute { }`, no
     * future.get(), no cross-Ops dispatch, no blocking IO. The block never waits
     * on another main-thread task, so holding this lock can't deadlock.
     *
     * The lock is reentrant so a future nested `withFakePlayer` call (e.g. via
     * a helper) doesn't self-deadlock.
     */
    private val interactionLock = ReentrantLock()

    fun get(server: MinecraftServer, world: ServerWorld): ServerPlayerEntity {
        val key = server to world
        cache[key]?.let { if (!it.isRemoved) return it }
        val p = create(server, world, PROFILE)
        cache[key] = p
        return p
    }

    fun create(server: MinecraftServer, world: ServerWorld, profile: GameProfile): ServerPlayerEntity {
        val p = ServerPlayerEntity(server, world, profile)
        // Resting state: survival + protected (a spawned player must never die or drift).
        p.abilities.creativeMode = false
        p.abilities.allowModifyWorld = true
        p.abilities.flying = false
        p.abilities.allowFlying = false
        ensureProtected(p)

        // Install a no-op network handler so mod code that calls
        // player.openHandledScreen() / player.sendMessageToClient() /
        // Criteria.trigger() / etc. doesn't NPE on null networkHandler.
        // Packets are silently dropped — no real client receives them.
        val connection = ClientConnection(NetworkSide.CLIENTBOUND)
        val handler = object : ServerPlayNetworkHandler(server, connection, p) {
            override fun sendPacket(packet: Packet<*>) {
                // no-op: silently drop all outbound packets
            }
        }
        p.networkHandler = handler

        // Spawn into the world through the same path real players use
        // (world.players list + entity manager): the player is ticked every server
        // tick, visible to world.getPlayers() / entity queries, and fires
        // entity-load / join hooks. It is NOT in PlayerManager and never receives
        // packets.
        world.onPlayerConnected(p)
        allSpawned.add(p)

        return p
    }

    /**
     * Run [block] with the fake player for (server, world) while holding the
     * interaction lock. Applies [gamemode] (default SURVIVAL) for the duration
     * of the block and restores the resting state afterwards.
     */
    fun <T> withFakePlayer(
        server: MinecraftServer,
        world: ServerWorld,
        gamemode: GameMode = GameMode.SURVIVAL,
        block: (ServerPlayerEntity) -> T,
    ): T = interactionLock.withLock {
        val player = get(server, world)
        val oldAbilities = AbilitiesSnapshot.capture(player)
        val oldGameMode = player.interactionManager.gameMode
        try {
            applyGameMode(player, gamemode)
            block(player)
        } finally {
            oldAbilities.restore(player)
            player.changeGameMode(oldGameMode)
            ensureProtected(player)
        }
    }

    /**
     * Parse the optional `gamemode` param of a fake-player command.
     * Default: survival. Override: "creative".
     */
    fun gameModeFrom(p: JsonObject?): GameMode {
        val s = p?.getStringOrNull("gamemode") ?: return GameMode.SURVIVAL
        return when (s.lowercase()) {
            "survival" -> GameMode.SURVIVAL
            "creative" -> GameMode.CREATIVE
            else -> throw RpcException(
                RpcErrors.INVALID_PARAMS,
                "invalid gamemode: $s (allowed: survival, creative)"
            )
        }
    }

    /**
     * Set the fake player's game mode through the vanilla API
     * (ServerPlayerInteractionManager.changeGameMode) so game-mode hooks fire,
     * then re-assert the protection flags survival mode clears.
     */
    fun applyGameMode(player: ServerPlayerEntity, mode: GameMode) {
        player.changeGameMode(mode)
        player.abilities.allowModifyWorld = true
        ensureProtected(player)
    }

    /** Keep the spawned fake player from dying, falling or drifting. */
    fun ensureProtected(player: ServerPlayerEntity) {
        player.setInvulnerable(true)
        player.abilities.invulnerable = true
        player.setNoGravity(true)
        player.setVelocity(0.0, 0.0, 0.0)
        player.fallDistance = 0f
        player.setOnGround(true)
    }

    /** Remove a fake player from its world (used by ScreenOps session teardown). */
    fun discard(player: ServerPlayerEntity) {
        allSpawned.remove(player)
        cache.entries.removeIf { (_, p) -> p === player }
        val w = player.world
        if (!player.isRemoved && w is ServerWorld) {
            w.removePlayer(player, Entity.RemovalReason.DISCARDED)
        }
    }

    /** Register the keepalive tick. Called from McDebugMod's onServerStarted hook. */
    fun install() {
        if (installed) return
        installed = true
        ServerTickEvents.END_SERVER_TICK.register {
            // Prune removed players (killed / world unloaded) and pin the rest in place.
            cache.entries.removeIf { (_, p) ->
                if (p.isRemoved) { allSpawned.remove(p); true } else { pin(p); false }
            }
            allSpawned.removeIf { p ->
                if (p.isRemoved) true else { pin(p); false }
            }
        }
    }

    /** Called on server stopping: discard every spawned fake player. */
    fun shutdown() {
        installed = false
        allSpawned.forEach { p ->
            runCatching {
                if (!p.isRemoved) {
                    val w = p.world
                    if (w is ServerWorld) w.removePlayer(p, Entity.RemovalReason.DISCARDED)
                }
            }
        }
        allSpawned.clear()
        cache.clear()
    }

    private var installed = false

    private fun pin(player: ServerPlayerEntity) {
        player.setNoGravity(true)
        player.setVelocity(0.0, 0.0, 0.0)
        player.fallDistance = 0f
        player.setOnGround(true)
        player.setInvulnerable(true)
        player.abilities.invulnerable = true
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
        fun restore(player: ServerPlayerEntity) {
            player.abilities.invulnerable = invulnerable
            player.abilities.flying = flying
            player.abilities.allowFlying = allowFlying
            player.abilities.creativeMode = creativeMode
            player.abilities.allowModifyWorld = allowModifyWorld
            player.abilities.setFlySpeed(flySpeed)
            player.abilities.setWalkSpeed(walkSpeed)
        }

        companion object {
            fun capture(player: ServerPlayerEntity): AbilitiesSnapshot = AbilitiesSnapshot(
                invulnerable = player.abilities.invulnerable,
                flying = player.abilities.flying,
                allowFlying = player.abilities.allowFlying,
                creativeMode = player.abilities.creativeMode,
                allowModifyWorld = player.abilities.allowModifyWorld,
                flySpeed = player.abilities.flySpeed,
                walkSpeed = player.abilities.walkSpeed
            )
        }
    }
}
