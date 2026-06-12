package com.mcdebug.test

import java.io.File
import java.net.JarURLConnection
import java.net.URL
import java.net.URLDecoder
import java.lang.reflect.Modifier
import java.util.jar.JarFile
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

/**
 * Hand-rolled classpath scanner that finds mcdebug tests within a set of base
 * packages.
 *
 * Supported discovery modes:
 *   1. Classes annotated with [McDebugTests], returning each referenced test.
 *   2. Classes annotated with [McDebugTestPackages], returning top-level
 *      Kotlin `object`s implementing [McDebugTest] in those packages.
 *
 * Why hand-rolled: avoids `reflections` / `classgraph` dependencies.
 * The pattern mirrors ic2-fabric's `ClassScanner.kt` but with no
 * coupling to ic2 types — it lives entirely in mcdebug and works for
 * any consuming mod.
 *
 * Walk strategy:
 *   1. For each base package, ask the [ClassLoader] for its resources
 *      (a `file:` URL for a directory in dev, a `jar:file:` URL for
 *      a packaged mod).
 *   2. Enumerate `.class` files under that URL.
 *   3. Load each class.
 *   4. Collect explicit `@McDebugTests` references and package-scoped
 *      `@McDebugTestPackages` test objects.
 *   5. Dedupe and return.
 *
 * Limitations:
 *   - Skips inner/anonymous classes (any class name containing `\$`).
 *   - Does not recurse into packages outside the given base set
 *     (so callers should list the *outermost* packages they care about).
 *   - One scan per server start; results are not cached across restarts.
 */
object TestDiscovery {
  /**
   * Scan [basePackages] for mcdebug tests.
   */
  fun discover(
    classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    basePackages: List<String>,
  ): List<KClass<out McDebugTest>> {
    val classNames = mutableSetOf<String>()
    for (pkg in basePackages) {
      val pkgPath = pkg.replace('.', '/')
      val resources = classLoader.getResources(pkgPath)
      while (resources.hasMoreElements()) {
        collectClassNames(resources.nextElement(), pkg, classNames)
      }
    }
    val classes = classNames.asSequence()
      .mapNotNull { runCatching { Class.forName(it, false, classLoader) }.getOrNull() }

    val loadedClasses = classes.toList()

    val explicit = loadedClasses.asSequence()
      .mapNotNull { it.kotlin.findAnnotation<McDebugTests>() }
      .flatMap { it.testClasses.asSequence() }

    val packageTests = loadedClasses.asSequence()
      .mapNotNull { it.kotlin.findAnnotation<McDebugTestPackages>() }
      .flatMap { it.packages.asSequence() }
      .distinct()
      .flatMap { discoverTestObjects(classLoader, it).asSequence() }

    return (explicit + packageTests)
      .distinct()
      .toList()
  }

  private fun discoverTestObjects(
    classLoader: ClassLoader,
    pkg: String,
  ): List<KClass<out McDebugTest>> {
    val classNames = mutableSetOf<String>()
    val resources = classLoader.getResources(pkg.replace('.', '/'))
    while (resources.hasMoreElements()) {
      collectClassNames(resources.nextElement(), pkg, classNames)
    }
    return classNames.asSequence()
      .mapNotNull { runCatching { Class.forName(it, false, classLoader) }.getOrNull() }
      .filter { McDebugTest::class.java.isAssignableFrom(it) }
      .filter { !it.isInterface && !Modifier.isAbstract(it.modifiers) }
      .map { it.asSubclass(McDebugTest::class.java).kotlin }
      .filter { it.objectInstance != null }
      .distinct()
      .toList()
  }

  private fun collectClassNames(url: URL, pkg: String, out: MutableSet<String>) {
    when (url.protocol) {
      "file" -> {
        val dir = File(URLDecoder.decode(url.path, Charsets.UTF_8))
        if (dir.isDirectory) walkDir(dir, pkg, out)
      }
      "jar" -> {
        val conn = (url.openConnection() as JarURLConnection)
        walkJar(conn.jarFile, conn.entryName, pkg, out)
      }
      // Ignore "jrt", "bundle", "wsjar" etc. — uncommon in Fabric dev
    }
  }

  private fun walkDir(dir: File, pkg: String, out: MutableSet<String>) {
    val files = dir.listFiles() ?: return
    for (f in files) {
      if (f.isDirectory) {
        walkDir(f, "$pkg.${f.name}", out)
      } else if (f.extension == "class" && !f.name.contains('\$')) {
        out.add("$pkg.${f.name.removeSuffix(".class")}")
      }
    }
  }

  private fun walkJar(jar: JarFile, entryPrefix: String, pkg: String, out: MutableSet<String>) {
    val prefix = "$entryPrefix/"
    val entries = jar.entries()
    while (entries.hasMoreElements()) {
      val e = entries.nextElement()
      if (e.isDirectory) continue
      if (!e.name.endsWith(".class")) continue
      if (!e.name.startsWith(prefix)) continue
      if (e.name.contains('\$')) continue  // skip inner/anonymous
      // Reconstruct the class name; pkg is used as a sanity check on the
      // discovered name (the JAR may contain classes from sibling packages
      // packed under the same prefix when entries nest deeply).
      val className = e.name.removeSuffix(".class").replace('/', '.')
      if (!className.startsWith(pkg)) continue
      out.add(className)
    }
  }
}
