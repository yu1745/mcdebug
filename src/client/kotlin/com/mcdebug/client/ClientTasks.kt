package com.mcdebug.client

import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Client-thread plumbing for the `gui.*` plane.
 *
 * Everything that touches the client (rendering, Screen dispatch, menus, resource
 * reloads) must run on the client main thread. `Minecraft extends
 * ReentrantBlockableEventLoop<Runnable>` and does not override `execute`, so
 * `client.execute { }` runs the task inline when it is already on the client
 * thread and queues it otherwise — one code path for both cases.
 *
 * The wait helpers ([awaitTicks], [until]) are *tick callbacks*, not active tick
 * drivers: they observe the natural client tick and can never advance the game
 * themselves (same rule as the server plane's `wait.until`).
 */
object ClientTasks {

    private class TickWaiter(val deadline: Long, val future: CompletableFuture<Unit>)

    private class CondWaiter(
        val deadline: Long,
        val check: () -> Boolean,
        val future: CompletableFuture<Boolean>,
    )

    @Volatile
    private var tick: Long = -1

    @Volatile
    private var installed: Boolean = false

    /** Touch only from the client thread (registration is client-thread-only by contract). */
    private val tickWaiters = ArrayList<TickWaiter>()
    private val condWaiters = ArrayList<CondWaiter>()

    fun install() {
        if (installed) return
        ClientTickEvents.END_CLIENT_TICK.register { _ -> onTick() }
        installed = true
    }

    /** Number of client ticks observed since the mod initialised (-1 before the first tick). */
    fun currentTick(): Long = tick

    /**
     * Run [body] on the client thread.
     *
     * When already on the client thread the body runs inline (RenderSystem /
     * Screen state is valid there); otherwise it is queued and runs before the
     * next client tick. The returned future completes on whichever thread ran
     * the body.
     */
    fun <T> onClient(client: Minecraft, body: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val run = Runnable {
            try {
                future.complete(body())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return try {
            client.execute(run)
            future
        } catch (t: Throwable) {
            future.completeExceptionally(t)
            future
        }
    }

    /** Blocking variant of [onClient]; never call this from the client thread. */
    fun <T> call(client: Minecraft, timeoutSeconds: Long = 30L, body: () -> T): T {
        val future = onClient(client, body)
        return try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is RpcException) throw cause
            throw RpcException(RpcErrors.INTERNAL_ERROR, "client task failed: ${cause?.message ?: e.message}", null, cause)
        } catch (e: RpcException) {
            throw e
        } catch (e: Exception) {
            throw RpcException(RpcErrors.CLIENT_NOT_READY, "client thread did not answer within ${timeoutSeconds}s: $e", null, e)
        }
    }

    /**
     * Complete after [ticks] client ticks. Must be called on the client thread.
     * `ticks <= 0` completes immediately.
     */
    fun awaitTicks(ticks: Int): CompletableFuture<Unit> {
        if (ticks <= 0) return CompletableFuture.completedFuture(Unit)
        val future = CompletableFuture<Unit>()
        tickWaiters.add(TickWaiter(tick + ticks, future))
        return future
    }

    /**
     * Poll [check] on every client tick until it is true or [maxTicks] ticks have
     * elapsed; completes with the check result (`false` = timed out). Must be
     * called on the client thread.
     */
    fun until(maxTicks: Int, check: () -> Boolean): CompletableFuture<Boolean> {
        val satisfied = check()
        if (satisfied || maxTicks <= 0) return CompletableFuture.completedFuture(satisfied)
        val future = CompletableFuture<Boolean>()
        condWaiters.add(CondWaiter(tick + maxTicks, check, future))
        return future
    }

    /**
     * Run [body] on the client thread once [future] has completed, handing it the
     * future's value. Keeps the "client state is only touched on the client thread"
     * rule intact when the preceding future happened to complete inline (e.g.
     * `awaitTicks(0)`), in which case a plain `thenApply` would run on the RPC thread.
     */
    fun <T, R> thenOnClient(client: Minecraft, future: CompletableFuture<T>, body: (T) -> R): CompletableFuture<R> =
        future.thenCompose { value -> onClient(client) { body(value) } }

    fun reset() {
        tickWaiters.clear()
        condWaiters.clear()
    }

    private fun onTick() {
        tick++
        if (tickWaiters.isNotEmpty()) {
            val iterator = tickWaiters.iterator()
            while (iterator.hasNext()) {
                val waiter = iterator.next()
                if (tick >= waiter.deadline) {
                    iterator.remove()
                    waiter.future.complete(Unit)
                }
            }
        }
        if (condWaiters.isNotEmpty()) {
            val iterator = condWaiters.iterator()
            while (iterator.hasNext()) {
                val waiter = iterator.next()
                val satisfied = try {
                    waiter.check()
                } catch (t: Throwable) {
                    iterator.remove()
                    waiter.future.completeExceptionally(t)
                    continue
                }
                if (satisfied) {
                    iterator.remove()
                    waiter.future.complete(true)
                } else if (tick >= waiter.deadline) {
                    iterator.remove()
                    waiter.future.complete(false)
                }
            }
        }
    }
}
