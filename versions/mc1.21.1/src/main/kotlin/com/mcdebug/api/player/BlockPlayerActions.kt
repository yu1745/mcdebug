package com.mcdebug.api.player

import com.mcdebug.api.FakePlayerPool
import com.mcdebug.api.getBoolOrFalse
import com.mcdebug.api.requirePos
import com.mcdebug.api.requireString
import com.mcdebug.api.requireDirection
import com.mcdebug.api.getStringOrNull
import com.mcdebug.api.posOrNull
import com.mcdebug.api.getDirectionOrNull
import com.mcdebug.api.getIntOr
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mcdebug.rpc.RpcContext
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import com.mcdebug.util.NbtJson
import com.mcdebug.util.ServerContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import com.google.common.collect.LinkedHashMultimap
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.core.Holder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import java.util.UUID
import java.util.concurrent.CompletableFuture
import com.mcdebug.loader.Loader

/**
 * Fake-player block gestures: place as player, right-click a block, and the two
 * break paths (instant tryBreakBlock vs the full START-hold-STOP mining gesture).
 * Split out of WorldPlayerActions; shared plumbing lives in [PlayerActionSupport].
 */
internal object BlockPlayerActions {

    /**
     * Place a block as if a player were clicking the side of an adjacent block.
     *
     * Goes through the full BlockItem / ItemPlacementContext pipeline so directional
     * blocks (stairs, doors, furnaces, ...) derive their state from the supplied
     * face / playerFacing instead of returning defaultState. Fires sounds, game
     * events, onPlaced, and Criteria.PLACED_BLOCK. Can fail (canPlace()=false)
     * if the target pos is non-replaceable.
     *
     * The placer is a stable fake ServerPlayer (UUID 8c0a4d6e-..., name
     * "[mcdebug_fake_player]"), spawned into the world and shared across all calls
     * and all dims, so mods that record "placed by" see a consistent identity.
     * Game mode defaults to SURVIVAL (the placed item is consumed from the hand);
     * pass `gamemode=creative` to place without consuming. Advancement / stat
     * triggers fire but on the fake player's tracker (not a real player's), so
     * they have no observable effect.
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
     *   gamemode       string  optional   survival (default) or creative
     *   dim            string  optional
     */
    fun placeAsPlayer(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = p.requirePos("pos")
            val blockId = p.requireString("block")
            val face = p.requireDirection("face")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
    
            val neighbor: BlockPos = p.posOrNull("neighbor")
                ?: pos.relative(face.opposite)
    
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
            val componentsEl = p.get("components")
            if (nbtEl != null && !nbtEl.isJsonNull) {
                ServerContext.stackSetNbt(stack, NbtJson.fromJson(nbtEl) as? CompoundTag)
            }
            if (componentsEl != null && !componentsEl.isJsonNull) {
                stack.applyComponents(ServerContext.componentsFromJson(server, componentsEl))
            }
    
            val gamemode = FakePlayerPool.gameModeFrom(p)
            var ok = false
            var itemAfter = ItemStack.EMPTY
            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                // Auto-position: keep the current spot if it can reach the click and does
                // not obstruct the placement target; otherwise teleport out of the way.
                PlayerActionSupport.positionForPlacement(
                    fakePlayer, pos, neighbor, face,
                    PlayerActionSupport.yawFor(playerFacing), PlayerActionSupport.pitchFor(playerFacing),
                )
    
                // Click point: center of the neighbor face that was hit, offset by face*0.5
                // (the EXACT world point the raycast would have struck).
                val hitPos = Vec3(
                    neighbor.x + 0.5 + face.stepX * 0.5,
                    neighbor.y + 0.5 + face.stepY * 0.5,
                    neighbor.z + 0.5 + face.stepZ * 0.5
                )
                val hitResult = BlockHitResult(hitPos, face, neighbor, false)
                fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, stack.copy())
    
                // Real path: the full right-click pipeline
                // (ServerPlayerInteractionManager.interactBlock) — the exact method a real
                // player's click goes through: UseBlockCallback (fabric mixin at HEAD) →
                // BlockState.onUse on the clicked neighbor → ItemStack.useOnBlock →
                // BlockItem.place, plus cooldown check and Criteria.ITEM_USED_ON_BLOCK.
                // Note: if the neighbor block consumes the click itself (button, lever,
                // chest, ...), the placement is skipped — exactly like a real player.
                val before = world.getBlockState(pos)
                val result: InteractionResult = fakePlayer.gameMode.useItemOn(
                    fakePlayer, world, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hitResult
                )
                val after = world.getBlockState(pos)
                ok = result.consumesAction()
                itemAfter = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND)
    
                JsonObject().apply {
                    add("pos", ServerContext.posAsJson(pos))
                    add("neighbor", ServerContext.posAsJson(neighbor))
                    addProperty("ok", ok)
                    addProperty("face", face.getName())
                    addProperty("playerFacing", playerFacing.getName())
                    addProperty("placer", fakePlayer.name.string)
                    addProperty("placerUuid", fakePlayer.getStringUUID())
                    addProperty("gamemode", gamemode.name.lowercase())
                    add("itemAfter", ServerContext.itemStackToJson(itemAfter))
                    add("previous", ServerContext.blockStateToJson(before))
                    add("state", ServerContext.blockStateToJson(after))
                }
            }
        }
    
    /**
     * Simulate left-clicking (attacking) a block — the full real mining gesture.
     *
     * Routes through ServerPlayerInteractionManager.processBlockBreakingAction with
     * the exact sequence a real player sends: START_DESTROY_BLOCK → (hold: the same
     * interactionManager.update() the server tick runs, fast-forwarded until the
     * vanilla >= 70% stop-check passes) → STOP_DESTROY_BLOCK. AttackBlockCallback
     * fires automatically (fabric mixin at HEAD of processBlockBreakingAction); a
     * consuming handler cancels everything.
     *
     * Semantics:
     *   - creative: the block breaks instantly (vanilla creative destroy).
     *   - survival: the block breaks when the stop-check passes; the held tool is
     *     used for mining speed and the final tryBreakBlock loot/damage.
     *   - unbreakable blocks (bedrock, barrier): nothing happens; the block is kept.
     *
     * Params:
     *   pos            [x,y,z] required   block being left-clicked
     *   face           string  required   which face was hit
     *   item           string  optional   item id to hold (default: empty hand)
     *   count          int     optional   stack size (default 1)
     *   nbt            object  optional   item NBT
     *   armor          object  optional   armor stacks keyed by head/chest/legs/feet;
     *                                    each value is {item,count,nbt}
     *   gamemode       string  optional   survival (default) or creative
     *   dim            string  optional
     *
     * Returns:
     *   broken           boolean  true if the block was broken
     *   eventConsumed    boolean  true if a Fabric API AttackBlockCallback handler
     *                              consumed the interaction (e.g. IC2 wrench disassembly)
     *                              — inferred: not broken and block state unchanged
     *   pos, face, gamemode, itemBefore, itemAfter, blockState
     */
    /**
     * Simulate a player breaking a block through the real server-side break
     * pipeline (ServerPlayerInteractionManager.tryBreakBlock).
     *
     * Unlike `attackBlock` (which simulates the mining gesture START→hold→STOP), this
     * breaks the block immediately through the vanilla tryBreakBlock path: canMine
     * check, creative instant break / survival drops + tool postMine, break events.
     * A cancelled break (protection mods) returns broken=false and the block is kept.
     *
     * Params (same shape as attackBlock):
     *   pos            [x,y,z] required
     *   face           string  required   which face was hit (used for aim/placement)
     *   item           string  optional   item id to hold (default: empty hand)
     *   count          int     optional
     *   nbt            object  optional
     *   armor          object  optional
     *   gamemode       string  optional   survival or creative (default survival)
     *   dim            string  optional
     *
     * Returns:
     *   broken           boolean  true if the block was actually broken
     *   pos, face, itemBefore, itemAfter, blockState
     */
    fun breakBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = p.requirePos("pos")
            val face = p.requireDirection("face")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
            val gamemode = FakePlayerPool.gameModeFrom(p)
    
            val itemBefore: ItemStack = if (p.has("item") && !p.get("item").isJsonNull) {
                val itemId = p.requireString("item")
                val count = p.getIntOr("count", 1)
                val nbtEl = p.get("nbt")
                val componentsEl = p.get("components")
                ServerContext.itemStackFromJson(server, itemId, count, nbtEl, componentsEl)
            } else {
                ItemStack.EMPTY
            }
    
            var broken = false
            var itemAfter = ItemStack.EMPTY
    
            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                val oldArmor = PlayerActionSupport.installArmor(server, fakePlayer, p)
    
                try {
                    fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, itemBefore.copy())
                    PlayerActionSupport.primeStackForGameplay(world, fakePlayer, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND), 0, true)
    
                    val lookDir = face.opposite
                    fakePlayer.moveTo(
                        pos.x + 0.5 + face.stepX * 1.0,
                        pos.y + 0.5 + face.stepY * 1.0,
                        pos.z + 0.5 + face.stepZ * 1.0,
                        PlayerActionSupport.yawFor(lookDir), PlayerActionSupport.pitchFor(lookDir)
                    )
    
                    // Real path: ServerPlayerInteractionManager.tryBreakBlock — the full
                    // server-side break pipeline (canMine check, creative instant break /
                    // survival drops + tool postMine, break events).
                    if (!world.getBlockState(pos).isAir) {
                        broken = fakePlayer.gameMode.destroyBlock(pos)
                    }
    
                    itemAfter = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND).copy()
                } finally {
                    fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                    PlayerActionSupport.restoreArmor(fakePlayer, oldArmor)
                    fakePlayer.isShiftKeyDown = false
                }
            }
    
            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("face", face.getName())
                addProperty("broken", broken)
                addProperty("gamemode", gamemode.name.lowercase())
                add("itemBefore", ServerContext.itemStackToJson(itemBefore))
                add("itemAfter", ServerContext.itemStackToJson(itemAfter))
                add("blockState", ServerContext.blockStateToJson(world.getBlockState(pos)))
            }
        }
    
    fun attackBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = p.requirePos("pos")
            val face = p.requireDirection("face")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
    
            val itemBefore: ItemStack = if (p.has("item") && !p.get("item").isJsonNull) {
                val itemId = p.requireString("item")
                val count = p.getIntOr("count", 1)
                val nbtEl = p.get("nbt")
                val componentsEl = p.get("components")
                ServerContext.itemStackFromJson(server, itemId, count, nbtEl, componentsEl)
            } else {
                ItemStack.EMPTY
            }
    
            var broken = false
            var eventConsumed = false
            var itemAfter = ItemStack.EMPTY
            val gamemode = FakePlayerPool.gameModeFrom(p)
    
            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                val oldArmor = PlayerActionSupport.installArmor(server, fakePlayer, p)
    
                try {
                    fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, itemBefore.copy())
                    PlayerActionSupport.primeStackForGameplay(world, fakePlayer, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND), 0, true)
    
                    val lookDir = face.opposite
                    fakePlayer.moveTo(
                        pos.x + 0.5 + face.stepX * 1.0,
                        pos.y + 0.5 + face.stepY * 1.0,
                        pos.z + 0.5 + face.stepZ * 1.0,
                        PlayerActionSupport.yawFor(lookDir), PlayerActionSupport.pitchFor(lookDir)
                    )
    
                    val stateBefore = world.getBlockState(pos)
                    if (!stateBefore.isAir) {
                        val im = fakePlayer.gameMode
                        // Real mining gesture: START → hold (fast-forwarded through the same
                        // interactionManager.update() the server tick runs) → STOP.
                        // AttackBlockCallback fires automatically (fabric mixin at HEAD of
                        // processBlockBreakingAction); a consuming handler cancels everything.
                        im.handleBlockBreakAction(
                            pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, face, world.maxBuildHeight, 0
                        )
                        if (!world.getBlockState(pos).isAir) {
                            // Survival: mine until the vanilla stop-check (>= 70% progress) passes.
                            val delta = stateBefore.getDestroyProgress(fakePlayer, world, pos)
                            val holdTicks = if (delta > 0f) {
                                ceil(0.7 / delta - 1.0).toInt().coerceAtLeast(0)
                            } else 0
                            repeat(holdTicks) { im.tick() }
                            im.handleBlockBreakAction(
                                pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, face, world.maxBuildHeight, 0
                            )
                        }
                    }
    
                    val stateAfter = world.getBlockState(pos)
                    broken = !stateBefore.isAir && stateAfter.isAir
                    // A consuming AttackBlockCallback cancels the whole method, leaving the
                    // block untouched (same observable as a refused break).
                    eventConsumed = !broken && stateAfter == stateBefore
                    itemAfter = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND).copy()
                } finally {
                    fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                    PlayerActionSupport.restoreArmor(fakePlayer, oldArmor)
                    fakePlayer.isShiftKeyDown = false
                }
            }
    
            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("face", face.getName())
                addProperty("broken", broken)
                addProperty("eventConsumed", eventConsumed)
                addProperty("gamemode", gamemode.name.lowercase())
                add("itemBefore", ServerContext.itemStackToJson(itemBefore))
                add("itemAfter", ServerContext.itemStackToJson(itemAfter))
                add("blockState", ServerContext.blockStateToJson(world.getBlockState(pos)))
            }
        }

    /**
     * Simulate right-clicking (using) a block with an item in hand.
     *
     * Routes through the real ServerPlayerInteractionManager.interactBlock — the exact
     * method a real player's right-click goes through. The pipeline is:
     *   0. UseBlockCallback (fabric mixin at HEAD of interactBlock) — mod handlers
     *      (wrench, etc.) register here. If non-PASS, the interaction is consumed and
     *      vanilla phases are skipped.
     *   1. BlockState.onUse(world, player, hand, hit) — the block handles the interaction
     *      (e.g. lever toggles, button presses, door opens, chest/furnace opens GUI).
     *      Skipped if the player is sneaking with an item in hand (vanilla sneaking rule).
     *   2. If the block returns PASS, ItemStack.useOnBlock(context) — the item handles it
     *      (e.g. bucket picks up water, flint-and-steel ignites, fluid cell injects fluid).
     *
     * The fake player is positioned just outside the target face. Its facing direction
     * is controlled by the `playerFacing` param (default: looking at the clicked face).
     * Game mode defaults to SURVIVAL (stacks are consumed / containers drain properly);
     * pass `gamemode=creative` to preserve stacks.
     *
     * Params:
     *   pos            [x,y,z] required   block being right-clicked
     *   face           string  required   which face to click: up|down|north|south|east|west
     *   item           string  optional   item id to hold (default: empty hand)
     *   count          int     optional   stack size (default 1)
     *   nbt            object  optional   item NBT (e.g. {"Energy": 30000} for batteries)
     *   sneaking       bool    optional   player.isShiftKeyDown (default false)
     *   playerFacing   string  optional   which direction the player is facing (one of
     *                                    north|south|east|west). Controls player.horizontalFacing
     *                                    which mods read for rotation. Default: face.opposite
     *                                    (player looks at the clicked face).
     *   gamemode       string  optional   survival (default) or creative
     *   dim            string  optional
     *
     * Returns:
     *   success         boolean  true if any handler consumed the interaction
     *   action          string   "success" | "fail" | "pass"
     *   eventConsumed   boolean  the UseBlockCallback consumed the click with no vanilla
     *                             side effect (e.g. a protection mod denying). Heuristic:
     *                             non-PASS result with item/state/screen unchanged.
     *   blockConsumed   boolean  BlockState.onUse handled it (lever, chest, ...) or the
     *                             block state changed. Heuristic, see code.
     *   itemConsumed    boolean  ItemStack.useOnBlock handled it and changed the stack.
     *   sneaking        boolean  the effective sneaking state used
     *   playerFacing    string   the effective player facing direction used
     *   gamemode        string   the resolved game mode
     *   itemBefore      object   item stack before interaction
     *   itemAfter       object   item stack after interaction
     *   blockState      object   block state AFTER the interaction
     */
    fun useOnBlock(server: MinecraftServer, params: JsonObject?): CompletableFuture<JsonElement> =
        RpcContext.onServer(server) {
            val p = params ?: throw RpcException(RpcErrors.INVALID_PARAMS, "params required")
            val pos = p.requirePos("pos")
            val face = p.requireDirection("face")
            val world = ServerContext.world(server, p.getStringOrNull("dim"))
    
            // Parse held item (optional — empty hand if omitted)
            val itemBefore: ItemStack = if (p.has("item") && !p.get("item").isJsonNull) {
                val itemId = p.requireString("item")
                val count = p.getIntOr("count", 1)
                val nbtEl = p.get("nbt")
                val componentsEl = p.get("components")
                ServerContext.itemStackFromJson(server, itemId, count, nbtEl, componentsEl)
            } else {
                ItemStack.EMPTY
            }
    
            // Set up fake player with the item in main hand
            val sneaking = p.getBoolOrFalse("sneaking")
            // Interaction game mode: default survival (held item is consumed / containers
            // are properly drained); "creative" preserves stacks (vanilla creative rules).
            val gamemode = FakePlayerPool.gameModeFrom(p)
    
            // Player facing: explicit override or default to looking at the clicked face
            val playerFacing = p.getDirectionOrNull("playerFacing")
                ?: face.opposite.let { if (it.axis == Direction.Axis.Y) Direction.NORTH else it }
    
            lateinit var eventResult: InteractionResult
            lateinit var blockResult: InteractionResult
            lateinit var itemResult: InteractionResult
            var itemAfter: ItemStack = ItemStack.EMPTY
            var creativeModeSnapshot: Boolean? = null
    
            FakePlayerPool.withFakePlayer(server, world, gamemode) { fakePlayer ->
                fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, itemBefore.copy())
                fakePlayer.isShiftKeyDown = sneaking
    
                // Auto-position: keep the current spot if it can reach the click and does
                // not obstruct the block this interaction may place (pos + face);
                // otherwise teleport to the far side of the clicked block. yaw follows
                // playerFacing so player.horizontalFacing matches it.
                PlayerActionSupport.positionForPlacement(
                    fakePlayer, pos.relative(face), pos, face,
                    PlayerActionSupport.yawFor(playerFacing), PlayerActionSupport.pitchFor(face.opposite),
                )
    
                // Hit point: center of the target face
                val hitPos = Vec3(
                    pos.x + 0.5 + face.stepX * 0.5,
                    pos.y + 0.5 + face.stepY * 0.5,
                    pos.z + 0.5 + face.stepZ * 0.5
                )
                val hitResult = BlockHitResult(hitPos, face, pos, false)
    
                val stateBefore = world.getBlockState(pos)
                val itemBeforeSnapshot = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND).copy()
    
                // Real path: ServerPlayerInteractionManager.interactBlock — the exact method
                // a real right-click goes through: UseBlockCallback (fabric mixin at HEAD)
                // → BlockState.onUse (with the vanilla sneaking rule) → ItemStack.useOnBlock,
                // plus the cooldown check and Criteria.ITEM_USED_ON_BLOCK.
                val result = fakePlayer.gameMode.useItemOn(
                    fakePlayer, world, fakePlayer.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hitResult
                )
    
                val stateAfter = world.getBlockState(pos)
                itemAfter = fakePlayer.getItemInHand(InteractionHand.MAIN_HAND).copy()
                creativeModeSnapshot = fakePlayer.abilities.instabuild
    
                // The phase breakdown is inferred from observable side effects (the vanilla
                // method does not expose per-phase results):
                //   - eventConsumed: the callback consumed the click with no vanilla side
                //     effect (e.g. a protection mod denying → FAIL, nothing changed).
                //   - itemConsumed: the held stack changed (bucket pickup, survival placement...).
                //   - blockConsumed: block.onUse handled it (lever, chest...) or the block
                //     state changed (creative placement).
                val itemChanged = !ItemStack.matches(itemBeforeSnapshot, itemAfter)
                val stateChanged = stateBefore != stateAfter
                val screenOpened = fakePlayer.containerMenu !== fakePlayer.inventoryMenu
                if (screenOpened) {
                    // block.onUse opened a container GUI — close it so the shared fake
                    // player does not keep a stale open screen between calls.
                    fakePlayer.closeContainer()
                }
    
                eventResult = if (result != InteractionResult.PASS && !itemChanged && !stateChanged && !screenOpened) {
                    result
                } else {
                    InteractionResult.PASS
                }
                blockResult = if (result.consumesAction() && !itemChanged && eventResult == InteractionResult.PASS) result else InteractionResult.PASS
                itemResult = if (result.consumesAction() && itemChanged) result else InteractionResult.PASS
    
                // Clean up: clear hand and reset sneaking (mode/abilities restored by the pool).
                fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                fakePlayer.isShiftKeyDown = false
            }
    
            val success = eventResult.consumesAction() || blockResult.consumesAction() || itemResult.consumesAction()
            val consumed = when {
                eventResult.consumesAction() || eventResult == InteractionResult.FAIL -> eventResult
                blockResult.consumesAction() -> blockResult
                itemResult.consumesAction() -> itemResult
                else -> InteractionResult.PASS
            }
    
            JsonObject().apply {
                add("pos", ServerContext.posAsJson(pos))
                addProperty("success", success)
                addProperty("action", consumed.name.lowercase())
                addProperty("face", face.getName())
                addProperty("sneaking", sneaking)
                addProperty("playerFacing", playerFacing.getName())
                addProperty("gamemode", gamemode.name.lowercase())
                addProperty("creativeMode", creativeModeSnapshot ?: false)
                addProperty("eventConsumed", eventResult.consumesAction() || eventResult == InteractionResult.FAIL)
                addProperty("blockConsumed", blockResult.consumesAction())
                addProperty("itemConsumed", itemResult.consumesAction())
                add("itemBefore", ServerContext.itemStackToJson(itemBefore))
                add("itemAfter", ServerContext.itemStackToJson(itemAfter))
                add("blockState", ServerContext.blockStateToJson(world.getBlockState(pos)))
            }
        }
}
