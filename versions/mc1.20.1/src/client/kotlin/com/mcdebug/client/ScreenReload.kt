package com.mcdebug.client

import com.google.gson.JsonObject
import com.mcdebug.McDebug
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Child-first only for the selected classes (the open Screen's class, its nested
 * classes, explicit extra prefixes); everything else — the mod's registries,
 * singletons, Minecraft — resolves through the normal mod class loader, so nothing
 * is initialised twice.
 */
internal class ScreenClassLoader(
    private val dirs: List<Path>,
    parent: ClassLoader,
    private val reload: (String) -> Boolean,
) : ClassLoader("mcdebug-screen-reload", parent) {
    val defined = ArrayList<String>()

    fun file(name: String): Path? =
        dirs.map { it.resolve(name.replace('.', '/') + ".class") }.firstOrNull { Files.isRegularFile(it) }

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        findLoadedClass(name) ?: run {
            val file = if (reload(name)) file(name) else null
            if (file == null) return super.loadClass(name, resolve)
            val bytes = Files.readAllBytes(file)
            defined.add(name)
            defineClass(name, bytes, 0, bytes.size)
        }.also { if (resolve) resolveClass(it) }
    }
}

/**
 * `gui.reloadScreen`: replace the open Screen with a fresh instance of its class
 * loaded from compiled output in a new class loader. Unlike hotswap this accepts any
 * change to that class (new fields, methods, lambdas, widgets) on a standard JVM.
 *
 * Container screens are rebuilt around the *same* menu (constructor `(menu,
 * inventory, title)` or any other shape [ScreenArgs] can fill) and swapped in
 * without `removed()`, so the server-side container stays open and slots keep their
 * state. Plain screens get the old screen's parent (first Screen-typed field). The new
 * instance starts fresh: fields of the old Screen are not copied.
 *
 * Limits: classes outside the reloaded set are the loaded versions (use hotswap or
 * `also` for them); Java package-private access from reloaded classes to the rest
 * of the package fails (a different runtime package); Mixins do not apply to the
 * reloaded copy; reopening the GUI normally later uses the originally loaded class.
 */
internal object ScreenReload {
    private var reloads = 0

    /** Client thread. */
    fun reload(client: Minecraft, dirs: List<Path>, also: List<String>): JsonObject {
        val old = client.screen ?: throw noScreen()
        val root = old.javaClass.name
        val base = ScreenFactory::class.java.classLoader
        val loader = ScreenClassLoader(dirs, base) { name ->
            name == root || name.startsWith("$root$") || also.any { name == it || name.startsWith(it) }
        }
        loader.file(root) ?: throw RpcException(RpcErrors.INVALID_PARAMS,
            "$root.class not found under ${dirs.joinToString()} (pass the output directory that holds the open Screen's class)",
            reason("CLASS_NOT_FOUND"))
        val container = old as? AbstractContainerScreen<*>
        val args = ScreenArgs(client, client.player, parentOf(old), container?.getMenu(), old.title)
        val fresh = try {
            val type = Class.forName(root, true, loader)
            args.construct(type).takeIf { it !== ScreenArgs.NONE } as? Screen
                ?: throw RpcException(RpcErrors.INVALID_PARAMS,
                    "$root has no public constructor fillable from (menu, inventory, title, parent, player, minecraft, options)",
                    reason("NO_FACTORY"))
        } catch (e: RpcException) {
            throw e
        } catch (t: Throwable) {
            throw failure(root, "constructing", t)
        }
        try {
            if (container != null) {
                // Not setScreen: AbstractContainerScreen.removed() would close the menu
                // the new screen is built around.
                fresh.added()
                fresh.init(client, client.window.guiScaledWidth, client.window.guiScaledHeight)
                client.screen = fresh
            } else {
                client.setScreen(fresh)
            }
        } catch (t: Throwable) {
            if (client.screen === fresh) client.screen = old
            throw failure(root, "init()", t)
        }
        ScreenGuard.clear()
        reloads++
        McDebug.LOGGER.info("mcdebug reloadScreen: {} from {} ({} classes)", root, dirs, loader.defined.size)
        return JsonObject().apply {
            addProperty("ok", true)
            addProperty("screen", root)
            addProperty("reloads", reloads)
            add("classes", stringArray(loader.defined))
            addProperty("reusedMenu", container != null)
            addProperty("widgets", GuiWidgets.collect(fresh).size)
        }
    }

    /** Plain screens usually keep their parent in a Screen-typed field (lastScreen/parent). */
    private fun parentOf(screen: Screen): Screen? {
        var type: Class<*>? = screen.javaClass
        while (type != null && type != Screen::class.java) {
            for (field in type.declaredFields) {
                if (Screen::class.java.isAssignableFrom(field.type) && !java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                    val value = runCatching { field.isAccessible = true; field.get(screen) as? Screen }.getOrNull()
                    if (value != null && value !== screen) return value
                }
            }
            type = type.superclass
        }
        return null
    }

    private fun failure(root: String, phase: String, t: Throwable): RpcException {
        val cause = if (t is InvocationTargetException && t.cause != null) t.cause!! else t
        return RpcException(RpcErrors.INTERNAL_ERROR, "reloading $root failed in $phase: $cause (the old screen stays open)",
            reason("SCREEN_FAILED").apply { addProperty("trace", ProblemLog.describe(cause)) })
    }
}
