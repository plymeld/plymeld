package plymeld.cli

import plymeld.config.UserDirs
import java.nio.file.Files
import java.nio.file.Path

/**
 * The command line's java.home, which a native executable does not have. The JDK's fonts read it as they start (every
 * picture with text in it): they look for a font configuration in its `lib/` folder (the JDK's `fontconfig.bfc`, which
 * the Windows package ships there), and on Linux ask the system's fontconfig only when that folder is there. Without it
 * they have no configuration at all, and the first text drawn stops ("Fontconfig head is null"). So java.home is a
 * folder that holds `lib/`, however the command line was installed:
 *
 * - the executable's own folder, where its `lib/` is (as every download and package ships it);
 * - else the same folder, its `lib/` made now (an install whose unpacker or updater left out the empty folder);
 * - else, where that folder cannot be written, `java-home/` in plymeld's cache folder ([UserDirs.cache]), its `lib/`
 *   made there: the libraries beside the executable load from its own folder whatever java.home says, and on Linux the
 *   folder needs nothing in it;
 * - else the executable's folder as it is.
 *
 * A JVM has its own java.home and is left alone.
 */
object JavaHome {
    /** java.home for an executable in [exeFolder]: the first of it and [fallback] that holds a `lib/`, or where [make] makes one. */
    fun choose(exeFolder: Path, fallback: Path, make: (Path) -> Boolean = ::makeFolder): Path =
        listOf(exeFolder, fallback).firstOrNull { home -> home.resolve("lib").let { Files.isDirectory(it) || make(it) } } ?: exeFolder

    /** Makes [folder] and those above it; false where it cannot (a folder that cannot be written, a file in the way). */
    fun makeFolder(folder: Path): Boolean = try { Files.createDirectories(folder); true } catch (_: Exception) { false }

    /** Sets java.home where this process has none (a native executable), by [choose]. */
    fun set() {
        if (System.getProperty("java.home") != null) return
        val exe = ProcessHandle.current().info().command().orElse(null) ?: return
        val folder = Path.of(exe).toAbsolutePath().parent ?: return
        System.setProperty("java.home", choose(folder, UserDirs.current().cache.resolve("java-home")).toString())
    }
}
