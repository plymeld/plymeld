package plymeld.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Path

/** Where plymeld keeps its own files on each system (UserDirs). Expected paths are each system's
 * documented convention, written out here, not derived from UserDirs. */
class UserDirsTest : StringSpec({
    fun dirs(d: UserDirs) = listOf(d.config, d.data, d.state, d.cache).map { it.toString().replace('\\', '/') }

    "Linux with no XDG variables: ~/.config, ~/.local/share, ~/.local/state, ~/.cache" {
        dirs(UserDirs.of(emptyMap(), "Linux", "/home/u")) shouldBe
            listOf("/home/u/.config/plymeld", "/home/u/.local/share/plymeld", "/home/u/.local/state/plymeld", "/home/u/.cache/plymeld")
    }

    "Linux: each XDG variable moves its own folder only; control: a blank one is ignored" {
        val env = mapOf("XDG_CONFIG_HOME" to "/c", "XDG_DATA_HOME" to "/d", "XDG_STATE_HOME" to "/s", "XDG_CACHE_HOME" to "/k")
        dirs(UserDirs.of(env, "Linux", "/home/u")) shouldBe listOf("/c/plymeld", "/d/plymeld", "/s/plymeld", "/k/plymeld")
        dirs(UserDirs.of(mapOf("XDG_STATE_HOME" to "  "), "Linux", "/home/u"))[2] shouldBe "/home/u/.local/state/plymeld"
    }

    "Windows: APPDATA for config and data, LOCALAPPDATA for state and cache" {
        val env = mapOf("APPDATA" to "C:/Users/u/AppData/Roaming", "LOCALAPPDATA" to "C:/Users/u/AppData/Local")
        dirs(UserDirs.of(env, "Windows 11", "C:/Users/u")) shouldBe listOf(
            "C:/Users/u/AppData/Roaming/plymeld", "C:/Users/u/AppData/Roaming/plymeld",
            "C:/Users/u/AppData/Local/plymeld/state", "C:/Users/u/AppData/Local/plymeld/cache")
    }

    "Windows without the variables falls back to the home folder's AppData; control: XDG variables are not read there" {
        val d = UserDirs.of(mapOf("XDG_CONFIG_HOME" to "/c"), "Windows 10", "/u")
        dirs(d) shouldBe listOf("/u/AppData/Roaming/plymeld", "/u/AppData/Roaming/plymeld", "/u/AppData/Local/plymeld/state", "/u/AppData/Local/plymeld/cache")
    }

    "macOS: Application Support for config and data, state under it, Caches for the cache" {
        dirs(UserDirs.of(mapOf("XDG_CONFIG_HOME" to "/c"), "Mac OS X", "/Users/u")) shouldBe listOf(
            "/Users/u/Library/Application Support/plymeld", "/Users/u/Library/Application Support/plymeld",
            "/Users/u/Library/Application Support/plymeld/state", "/Users/u/Library/Caches/plymeld")
    }

    "PLYMELD_HOME holds all four under it on every system; control: without it the folders differ" {
        for (os in listOf("Linux", "Windows 11", "Mac OS X")) {
            dirs(UserDirs.of(mapOf("PLYMELD_HOME" to "/p", "XDG_CONFIG_HOME" to "/c", "APPDATA" to "/a"), os, "/h")) shouldBe
                listOf("/p/config", "/p/data", "/p/state", "/p/cache")
            UserDirs.of(mapOf("XDG_CONFIG_HOME" to "/c", "APPDATA" to "/a"), os, "/h").config shouldNotBe Path.of("/p/config")
        }
    }
})
