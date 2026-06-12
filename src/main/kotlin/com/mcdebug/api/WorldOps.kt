package com.mcdebug.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.rpc.RpcHandler
import com.mcdebug.rpc.RpcHandlerGroup
import com.mcdebug.util.NbtJson
import com.mcdebug.util.ServerContext
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.item.BlockItem
import net.minecraft.item.ItemPlacementContext
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ChunkTicketType
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.concurrent.CompletableFuture

object WorldOps : RpcHandlerGroup {

    private const val DEFAULT_FLAGS = Block.NOTIFY_LISTENERS or Block.NOTIFY_NEIGHBORS or Block.REDRAW_ON_MAIN_THREAD

    override fun methods(): Map<String, RpcHandler> = mapOf(
        "getBlock" to ::getBlock,
        "setBlock" to ::setBlock,
        "setBlocks" to ::setBlocks,
        "placeAsPlayer" to ::placeAsPlayer,
        "getRegion" to ::getRegion,
        "selectBlocks" to ::selectBlocks,
        "forceloadChunk" to ::forceloadChunk,
        "unforceloadChunk" to ::unforceloadChunk
    )

    private fun getBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = ServerContext.pos(p.getAsJsonArray("pos"))
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val state = world.getBlockState(pos)
            val hasBe = world.getBlockEntity(pos) != null
            val includeNbt = p.getBoolOrFalse("includeNbt")
            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("dim", world.registryKey.value.toString())
                add("state", ServerContext.blockStateToJson(state))
                addProperty("hasBlockEntity", hasBe)
                if (includeNbt && hasBe) {
                    val nbt = world.getBlockEntity(pos)!!.createNbt()
                    add("nbt", NbtJson.toJson(nbt))
                }
            }
        }

    private fun setBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = ServerContext.pos(p.getAsJsonArray("pos"))
            val blockId = p.requireString("block")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val stateProps = p.getStringMapOrNull("stateProps")
            val flags = p.getIntOr("flags", DEFAULT_FLAGS)
            val state = ServerContext.blockState(server, blockId, stateProps)
            val previous = world.getBlockState(pos)
            val ok = world.setBlockState(pos, state, flags)
            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("ok", ok)
                add("previous", ServerContext.blockStateToJson(previous))
            }
        }

    private fun setBlocks(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val ops = p.getAsJsonArray("ops")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val flags = p.getIntOr("flags", DEFAULT_FLAGS)
            var count = 0
            ops.forEach { el ->
                val op = el.asJsonObject
                val pos = ServerContext.pos(op.getAsJsonArray("pos"))
                val block = ServerContext.blockState(server, op.requireString("block"), op.getStringMapOrNull("stateProps"))
                if (world.setBlockState(pos, block, flags)) count++
            }
            JsonObject().apply { addProperty("count", count) }
        }

    /**
     * Place a block as if a player were clicking the side of an adjacent block.
     *
     * Goes through the full BlockItem / ItemPlacementContext pipeline so directional
     * blocks (stairs, doors, furnaces, ...) derive their state from the supplied
     * face / playerFacing instead of returning defaultState. Fires sounds, game
     * events, onPlaced, and Criteria.PLACED_BLOCK. Can fail (canPlace()=false)
     * if the target pos is non-replaceable.
     *
     * The placer is a stable fake ServerPlayerEntity (UUID 8c0a4d6e-..., name
     * "[mcdebug_fake_player]"). This is shared across all calls and all dims, so
     * mods that record "placed by" see a consistent identity. The player is in
     * creative + invulnerable + flying mode, so the placed stack is not decremented
     * and survival-mode interceptions don't fire. Advancement / stat triggers fire
     * but on the fake player's tracker (not a real player's), so they have no
     * observable effect.
     *
     * Semantics of `playerFacing`: it is the direction the player is LOOKING when
     * they click — NOT the direction you want the placed block to face. Vanilla
     * blocks consume this differently:
     *   - stairs / doors / beds / banners:    block.facing = playerFacing
     *   - furnace / chest / dispenser / etc.:  block.facing = playerFacing.opposite
     *   - glass / stone / other non-directional: ignored
     * So to place a furnace whose `facing` should be `south`, pass
     * `playerFacing=north` (player looks north, furnace faces the opposite).
     *
     * Params:
     *   pos            [x,y,z] required   placement target
     *   block          string  required   block id (e.g. "minecraft:furnace")
     *   face           string  required   which side of `neighbor` was clicked:
     *                                    one of up|down|north|south|east|west
     *   neighbor       [x,y,z] optional   block that was clicked; default = pos - face
     *   playerFacing   string  optional   which way the player was looking when they
     *                                    clicked; default = face.opposite if
     *                                    horizontal, else north
     *   nbt            object  optional   ItemStack NBT (BlockEntityTag for chest,
     *                                    BlockStateTag for forcing state, etc.)
     *   dim            string  optional
     */
    private fun placeAsPlayer(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = ServerContext.pos(p.getAsJsonArray("pos"))
            val blockId = p.requireString("block")
            val face = p.requireDirection("face")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))

            val neighbor: BlockPos = p.getAsJsonArray("neighbor")?.let(ServerContext::pos)
                ?: pos.offset(face.opposite)

            val playerFacing: Direction = p.getDirectionOrNull("playerFacing")
                ?: face.opposite.let { if (it.axis == Direction.Axis.Y) Direction.NORTH else it }

            val block = ServerContext.blockById(server, blockId)
            val blockItem = block.asItem() as? BlockItem
                ?: throw RpcException(
                    RpcErrors.INVALID_PARAMS,
                    "block $blockId has no BlockItem (cannot use placeAsPlayer; try setBlock)"
                )

            val stack = ItemStack(blockItem, 1)
            val nbtEl = p.get("nbt")
            if (nbtEl != null && !nbtEl.isJsonNull) {
                stack.nbt = NbtJson.fromJson(nbtEl) as? NbtCompound
            }

            // Place the stable fake player at the placement pos with rotation set from
            // playerFacing. Real Entity.getYaw/getPitch flow into ItemPlacementContext's
            // getPlayerLookDirection / getHorizontalPlayerFacing / getPlayerYaw, so we
            // don't need to override those anymore.
            val fakePlayer = FakePlayerPool.get(server, world)
            fakePlayer.refreshPositionAndAngles(
                pos.x + 0.5, pos.y + 0.5, pos.z + 0.5,
                yawFor(playerFacing), pitchFor(playerFacing)
            )

            // Click point: center of the neighbor face that was hit, offset by face*0.5
            // (the EXACT world point the raycast would have struck).
            val hitPos = Vec3d(
                neighbor.x + 0.5 + face.offsetX * 0.5,
                neighbor.y + 0.5 + face.offsetY * 0.5,
                neighbor.z + 0.5 + face.offsetZ * 0.5
            )
            val hitResult = BlockHitResult(hitPos, face, neighbor, false)
            // Use the player-taking ctor so context.getPlayer() returns the fake player.
            val ctx = FixedTargetPlacementContext(fakePlayer, Hand.MAIN_HAND, stack, hitResult)

            val before = world.getBlockState(pos)
            val result: ActionResult = blockItem.place(ctx)
            val after = world.getBlockState(pos)
            val ok = result.isAccepted

            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                add("neighbor", ServerContext.posAsJson(neighbor))
                addProperty("ok", ok)
                addProperty("face", face.asString())
                addProperty("playerFacing", playerFacing.asString())
                addProperty("placer", fakePlayer.name.string)
                addProperty("placerUuid", fakePlayer.uuidAsString)
                add("previous", ServerContext.blockStateToJson(before))
                add("state", ServerContext.blockStateToJson(after))
            }
        }

    /**
     * Convert a horizontal Direction to the yaw angle (degrees) that, when passed
     * to Entity.setYaw, makes getYaw() → getEntityFacingOrder produce the same
     * direction. Matches vanilla: south=0, west=90, north=180, east=-90.
     * UP/DOWN map to a horizontal look (south) since getPlayerLookDirection()
     * for the Y axis uses getPitch, not yaw.
     */
    private fun yawFor(facing: Direction): Float = when (facing) {
        Direction.SOUTH -> 0f
        Direction.WEST -> 90f
        Direction.NORTH -> 180f
        Direction.EAST -> -90f
        Direction.UP, Direction.DOWN -> 0f
    }

    /**
     * Convert a vertical Direction to a pitch (degrees). UP = looking up = -90,
     * DOWN = looking down = +90. Horizontal directions stay level (pitch=0).
     */
    private fun pitchFor(facing: Direction): Float = when (facing) {
        Direction.UP -> -90f
        Direction.DOWN -> 90f
        else -> 0f
    }

    private fun getRegion(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val box = boxFromParams(p)
            val includeNbt = p.getBoolOrFalse("includeNbt")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            ensureChunkLoaded(world, box)
            val results = JsonArray()
            BlockPos.iterate(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ).forEach { pos ->
                val state = world.getBlockState(pos)
                val be = world.getBlockEntity(pos)
                val entry = JsonObject().apply {
                    add("pos", ServerContext.posAsJson(pos))
                    add("state", ServerContext.blockStateToJson(state))
                    addProperty("hasBlockEntity", be != null)
                    if (includeNbt && be != null) add("nbt", NbtJson.toJson(be.createNbt()))
                }
                results.add(entry)
            }
            JsonObject().apply { add("blocks", results) }
        }

    private fun selectBlocks(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val box = boxFromParams(p)
            val pred = p.getAsJsonObject("predicate")
                ?: throw RpcException(RpcErrors.INVALID_PARAMS, "predicate required")
            val blockId = pred.getStringOrNull("block")
            val includeNbt = p.getBoolOrFalse("includeNbt")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            ensureChunkLoaded(world, box)
            val target = if (blockId != null) ServerContext.blockById(server, blockId) else null
            val matches = JsonArray()
            BlockPos.iterate(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ).forEach { pos ->
                val state = world.getBlockState(pos)
                if (target != null && state.block != target) return@forEach
                val entry = JsonObject().apply { add("pos", ServerContext.posAsJson(pos)) }
                if (includeNbt) {
                    val be = world.getBlockEntity(pos)
                    if (be != null) entry.add("nbt", NbtJson.toJson(be.createNbt()))
                }
                matches.add(entry)
            }
            JsonObject().apply { add("matches", matches) }
        }

    /**
     * Force-load a chunk so its block entities tick.
     * Uses ServerWorld.setChunkForced (persistent, same as /forceload command).
     * This ensures the chunk stays in getForcedChunks(), which keeps tickBlockEntities() alive.
     */
    private fun forceloadChunk(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val chunkPos = chunkPosFromParams(p)
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val changed = world.setChunkForced(chunkPos.x, chunkPos.z, true)
            JsonObject().apply {
                add("chunk", JsonArray().apply { add(chunkPos.x); add(chunkPos.z) })
                addProperty("forced", true)
                addProperty("changed", changed)
                addProperty("dim", world.registryKey.value.toString())
            }
        }

    /**
     * Stop force-loading a chunk.
     */
    private fun unforceloadChunk(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val chunkPos = chunkPosFromParams(p)
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val changed = world.setChunkForced(chunkPos.x, chunkPos.z, false)
            JsonObject().apply {
                add("chunk", JsonArray().apply { add(chunkPos.x); add(chunkPos.z) })
                addProperty("forced", false)
                addProperty("changed", changed)
                addProperty("dim", world.registryKey.value.toString())
            }
        }

    private fun chunkPosFromParams(p: JsonObject): ChunkPos {
        val arr = p.getAsJsonArray("chunk")
            ?: throw RpcException(RpcErrors.INVALID_PARAMS, "chunk [cx,cz] required")
        if (arr.size() < 2) throw RpcException(RpcErrors.INVALID_PARAMS, "chunk must be [cx, cz]")
        return ChunkPos(arr.get(0).asInt, arr.get(1).asInt)
    }

    // ---- helpers ----

    private fun boxFromParams(p: JsonObject): BlockBox {
        val box = p.getAsJsonObject("box") ?: throw RpcException(RpcErrors.INVALID_PARAMS, "box required")
        val from = ServerContext.pos(box.getAsJsonArray("from"))
        val to = ServerContext.pos(box.getAsJsonArray("to"))
        return ServerContext.boxFrom(from, to)
    }

    private fun ensureChunkLoaded(world: World, box: BlockBox) {
        val minCx = box.minX shr 4
        val maxCx = box.maxX shr 4
        val minCz = box.minZ shr 4
        val maxCz = box.maxZ shr 4
        for (cx in minCx..maxCx) for (cz in minCz..maxCz) {
            // ChunkNotLoaded: we just check; auto-loading is left to v2
            if (!world.chunkManager.isChunkLoaded(cx, cz)) {
                throw RpcException(RpcErrors.CHUNK_NOT_LOADED, "chunk not loaded: ($cx, $cz)")
            }
        }
    }
}

// ---- json helpers ----

internal fun JsonObject.getString(name: String): String? =
    if (has(name) && !get(name).isJsonNull) get(name).asString else null

internal fun JsonObject.getString(name: String, default: String): String =
    getString(name) ?: default

internal fun JsonObject.requireString(name: String): String =
    getString(name) ?: throw RpcException(RpcErrors.INVALID_PARAMS, "missing string param: $name")

internal fun JsonObject.getStringOrNull(name: String): String? = getString(name)

internal fun JsonObject.getIntOr(name: String, default: Int): Int =
    if (has(name) && !get(name).isJsonNull) get(name).asInt else default

internal fun JsonObject.getBoolOrFalse(name: String): Boolean =
    has(name) && !get(name).isJsonNull && get(name).asBoolean

internal fun JsonObject.getStringMapOrNull(name: String): Map<String, String>? {
    if (!has(name) || get(name).isJsonNull) return null
    val obj = getAsJsonObject(name) ?: return null
    val out = mutableMapOf<String, String>()
    obj.entrySet().forEach { (k, v) ->
        if (v.isJsonPrimitive) out[k] = v.asString
    }
    return out
}

private val DIRECTION_NAMES = mapOf(
    "up" to Direction.UP,
    "down" to Direction.DOWN,
    "north" to Direction.NORTH,
    "south" to Direction.SOUTH,
    "east" to Direction.EAST,
    "west" to Direction.WEST,
)

internal fun JsonObject.requireDirection(name: String): Direction {
    val s = getString(name)
        ?: throw RpcException(RpcErrors.INVALID_PARAMS, "missing string param: $name")
    return DIRECTION_NAMES[s.lowercase()]
        ?: throw RpcException(
            RpcErrors.INVALID_PARAMS,
            "invalid $name: $s; expected one of ${DIRECTION_NAMES.keys.sorted()}"
        )
}

internal fun JsonObject.getDirectionOrNull(name: String): Direction? {
    val s = getString(name) ?: return null
    return DIRECTION_NAMES[s.lowercase()]
}

/**
 * ItemPlacementContext that always places at the caller-supplied placement pos,
 * ignoring the vanilla "replace neighbor if it's air" branch. Without this override,
 * placing on top of an air neighbor shifts the block to the neighbor's position,
 * which is wrong for our automation use case (callers always pass the intended
 * placement pos, not the neighbor).
 *
 * The vanilla parent class stores `placementPos = hitResult.blockPos.offset(hitResult.side)`
 * and `getBlockPos()` returns it when `canReplaceExisting` is false. We flip the
 * field in init, so the parent's getBlockPos() / canPlace() / getPlacementDirections()
 * all see the correct target without us having to override any of them.
 *
 * Uses the parent ctor that takes a ServerPlayerEntity so context.getPlayer()
 * returns the fake player (real placer for onPlaced / Criteria / GameEvent).
 */
private class FixedTargetPlacementContext(
    player: ServerPlayerEntity,
    hand: Hand,
    stack: ItemStack,
    hitResult: BlockHitResult,
) : ItemPlacementContext(player, hand, stack, hitResult) {
    init {
        canReplaceExisting = false
    }
}
