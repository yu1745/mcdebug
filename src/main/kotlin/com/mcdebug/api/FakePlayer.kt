package com.mcdebug.api

import com.mojang.authlib.GameProfile
import net.minecraft.network.ClientConnection
import net.minecraft.network.NetworkSide
import net.minecraft.network.packet.Packet
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayNetworkHandler
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Stable fake ServerPlayerEntity used as the placer for world.placeAsPlayer
 * and the interactor for world.useOnBlock / world.attackBlock.
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
 * Network handler:
 *   - The fake player has a no-op ServerPlayNetworkHandler that silently drops all
 *     outbound packets. This allows mod code that calls player.openHandledScreen(),
 *     player.sendMessageToClient(), Criteria.trigger(), etc. to work without NPE.
 *   - The handler is NOT ticked (the player is never spawned), so no keep-alive or
 *     position-sync packets are sent.
 *
 * Limitations vs a real player:
 *   - Packets are silently dropped — no real client receives them. This means GUI
 *     screens open server-side but the fake player can't interact with them.
 *   - Advancement triggers fire on the fake player's tracker, not visible to real players.
 *   - The player's inventory is managed per-call (set before use, cleared after).
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

    fun get(server: MinecraftServer, world: ServerWorld): ServerPlayerEntity =
        cache.getOrPut(server to world) { create(server, world, PROFILE) }

    fun create(server: MinecraftServer, world: ServerWorld, profile: GameProfile): ServerPlayerEntity {
        val p = ServerPlayerEntity(server, world, profile)
        // creative + invulnerable + flying so:
        //   - the placed/used stack isn't decremented (creative mode)
        //   - sound / GameEvent / onPlaced see a "creative" interactor
        p.abilities.creativeMode = true
        p.abilities.invulnerable = true
        p.abilities.flying = true
        p.setInvulnerable(true)

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

        return p
    }

    /**
     * Run [block] with the fake player for (server, world) while holding the
     * interaction lock. Use this instead of `get(...)` for any handler that
     * mutates fake-player state.
     */
    inline fun <T> withFakePlayer(
        server: MinecraftServer,
        world: ServerWorld,
        block: (ServerPlayerEntity) -> T,
    ): T = interactionLock.withLock { block(get(server, world)) }
}
