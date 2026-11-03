package plymeld.config

import java.nio.file.Path
import java.util.Locale

/**
 * The folders plymeld keeps its own files in, by each system's convention:
 *
 * - [config]: what the user sets (config files, the secrets file, the window's preferences);
 * - [data]: what the window writes and keeps (plan files and their journals);
 * - [state]: what plymeld writes for itself and may lose (logs, recent jobs, the search log);
 * - [cache]: what can be rebuilt at any time.
 *
 * `PLYMELD_HOME`, when set, holds all four as `config/`, `data/`, `state/` and `cache/` under it (a portable install, a
 * test's sandbox). Otherwise: on Windows `%APPDATA%\plymeld` (config, data) and `%LOCALAPPDATA%\plymeld\state` / `\cache`
 * (deliberately inside the installed app's folder, which its uninstaller deletes: the user's files stay, plymeld's go);
 * on macOS `~/Library/Application Support/plymeld` (config, data; `state/` under it) and `~/Library/Caches/plymeld`; elsewhere
 * the XDG base directories, `$XDG_CONFIG_HOME/plymeld` (else `~/.config/plymeld`), `$XDG_DATA_HOME` (`~/.local/share`),
 * `$XDG_STATE_HOME` (`~/.local/state`) and `$XDG_CACHE_HOME` (`~/.cache`). Nothing here makes a folder; a writer makes
 * the one it needs.
 */
class UserDirs(val config: Path, val data: Path, val state: Path, val cache: Path) {
    companion object {
        const val HOME_VARIABLE = "PLYMELD_HOME"

        /** The folders for this process: its environment, `os.name` and `user.home`. */
        fun current(): UserDirs = of(System.getenv(), System.getProperty("os.name"), System.getProperty("user.home"))

        /** The folders for [env], [osName] and [home], so every system's layout can be tested on any one. */
        fun of(env: Map<String, String>, osName: String, home: String): UserDirs {
            fun v(name: String): String? = env[name]?.takeIf { it.isNotBlank() }
            v(HOME_VARIABLE)?.let { h -> val r = Path.of(h); return UserDirs(r.resolve("config"), r.resolve("data"), r.resolve("state"), r.resolve("cache")) }
            val os = osName.lowercase(Locale.ROOT)
            return when {
                os.startsWith("windows") -> {
                    val roaming = Path.of(v("APPDATA") ?: Path.of(home, "AppData", "Roaming").toString(), "plymeld")
                    val local = Path.of(v("LOCALAPPDATA") ?: Path.of(home, "AppData", "Local").toString(), "plymeld")
                    UserDirs(roaming, roaming, local.resolve("state"), local.resolve("cache"))
                }
                os.startsWith("mac") -> {
                    val support = Path.of(home, "Library", "Application Support", "plymeld")
                    UserDirs(support, support, support.resolve("state"), Path.of(home, "Library", "Caches", "plymeld"))
                }
                else -> {
                    fun xdg(name: String, vararg fallback: String): Path = Path.of(v(name) ?: Path.of(home, *fallback).toString(), "plymeld")
                    UserDirs(xdg("XDG_CONFIG_HOME", ".config"), xdg("XDG_DATA_HOME", ".local", "share"),
                        xdg("XDG_STATE_HOME", ".local", "state"), xdg("XDG_CACHE_HOME", ".cache"))
                }
            }
        }
    }
}
