package plymeld.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import plymeld.config.UserDirs
import java.nio.file.Files
import java.nio.file.Path

/**
 * The window's colors (one accent, surfaces by elevation, light and dark). [dark] is the
 * one switch; every color is a getter on it, so a change recomposes everything. A per-viewer convenience, kept in
 * `window-theme.txt` in [UserDirs.config]; nothing in a print depends on it.
 */
internal object Ink {
    var dark by mutableStateOf(true)

    val bg: Color get() = if (dark) Color(0xFF15181C) else Color(0xFFF4F5F7)
    val surface: Color get() = if (dark) Color(0xFF1D2126) else Color(0xFFFFFFFF)
    val surface2: Color get() = if (dark) Color(0xFF23282E) else Color(0xFFF7F8FA)
    val surface3: Color get() = if (dark) Color(0xFF2C323A) else Color(0xFFE9ECF0)
    val line: Color get() = if (dark) Color(0xFF343A43) else Color(0xFFD0D5DD)
    val text: Color get() = if (dark) Color(0xFFE6E9EE) else Color(0xFF1A1F26)
    val muted: Color get() = if (dark) Color(0xFF9AA3B0) else Color(0xFF4B5563)
    val faint: Color get() = if (dark) Color(0xFF6B7380) else Color(0xFF7A8494)
    val accent: Color get() = if (dark) Color(0xFF6AA6FF) else Color(0xFF3B6FD8)
    val ok: Color get() = if (dark) Color(0xFF4CC38A) else Color(0xFF1F9D63)
    val warn: Color get() = if (dark) Color(0xFFF2B85A) else Color(0xFFB7791F)
    /** The locked / kept ink. */
    val hot: Color get() = if (dark) Color(0xFFFF8A65) else Color(0xFFC2410C)
    val onAccent: Color get() = if (dark) Color(0xFF0B1A2E) else Color(0xFFFFFFFF)
    /** A surface laid over the stage, letting it show through. */
    val glass: Color get() = surface.copy(alpha = 0.9f)
    val okTint: Color get() = ok.copy(alpha = 0.18f)
    val warnTint: Color get() = warn.copy(alpha = 0.2f)
    val swatchLine: Color get() = if (dark) Color(0x26FFFFFF) else Color(0x26000000)

    private val file: Path get() = UserDirs.current().config.resolve("window-theme.txt")
    fun load() { try { if (Files.exists(file)) dark = Files.readString(file).trim() != "light" } catch (_: Exception) { } }
    fun toggle() { dark = !dark; try { Files.createDirectories(file.parent); Files.writeString(file, if (dark) "dark\n" else "light\n") } catch (_: Exception) { } }
}
