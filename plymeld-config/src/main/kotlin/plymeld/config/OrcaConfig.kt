package plymeld.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * A QIDIStudio / OrcaSlicer settings set, as the source files store it: every value is a
 * string or a list of strings (one per extruder/filament slot). This is the IMPORT side
 * only — plymeld's own schema is [PrintSettings]; this class is the raw material.
 *
 * Every key read through [get] is recorded, so the set of keys plymeld does not understand
 * yet is always available ([unread]) — the parity checklist.
 */
class OrcaConfig(private val values: Map<String, List<String>>) {
    private val read = HashSet<String>()

    val keys: Set<String> get() = values.keys

    /** First value of a key (slot 0), or null. Falls back to the key's known alias. */
    operator fun get(key: String): String? = list(key)?.firstOrNull()
    fun list(key: String): List<String>? {
        read.add(key)
        values[key]?.let { return it }
        val alias = ALIASES[key] ?: return null
        read.add(alias)
        return values[alias]
    }
    fun require(key: String): String = get(key) ?: error("setting '$key' missing from the imported config")

    fun unread(): Set<String> = values.keys - read

    /** Raw values of a key without counting it as read (for carrying foreign template variables). */
    fun peek(key: String): List<String>? = values[key]

    /** A copy with one key replaced (slot 0 for vector settings; other slots kept). */
    fun with(key: String, value: String): OrcaConfig {
        val m = LinkedHashMap(values)
        val old = m[key]
        m[key] = if (old != null && old.size > 1) listOf(value) + old.drop(1) else listOf(value)
        return OrcaConfig(m)
    }

    companion object {
        /**
         * Same setting, different names across the Orca family (found by importing both profiles).
         * Keyed by the name plymeld asks for.
         */
        val ALIASES = mapOf(
            "sparse_infill_anchor_max" to "infill_anchor_max",   // QIDIStudio vs Orca / Flash Studio
            "infill_anchor_max" to "sparse_infill_anchor_max",
            "ironing_direction" to "ironing_angle",              // QIDIStudio vs Flash Studio
            "ironing_angle" to "ironing_direction",
        )

        fun parse(json: String): OrcaConfig {
            val obj = Json.parseToJsonElement(json) as JsonObject
            val m = LinkedHashMap<String, List<String>>()
            for ((k, v) in obj) {
                m[k] = when (v) {
                    is JsonArray -> v.map { (it as JsonPrimitive).content }
                    is JsonPrimitive -> listOf(v.content)
                    else -> continue
                }
            }
            return OrcaConfig(m)
        }

        fun load(path: Path): OrcaConfig = parse(Files.readString(path))

        /** The fully resolved settings a QIDIStudio/Orca project file was sliced with. */
        fun fromProject3mf(path: Path): OrcaConfig = ZipFile(path.toFile()).use { z ->
            val e = z.getEntry("Metadata/project_settings.config") ?: error("no Metadata/project_settings.config in $path")
            parse(z.getInputStream(e).bufferedReader().readText())
        }

        /**
         * The resolved settings Flash Studio / Orca-family slicers embed in their g-code between
         * `; CONFIG_BLOCK_START` and `; CONFIG_BLOCK_END` as `; key = value` lines. Values are
         * split on commas into per-slot vectors only when they are plain tokens (no spaces,
         * quotes, semicolons, or escaped newlines); templates and free text stay whole, with
         * `\n` unescaped. Reads only up to the end of the block.
         */
        fun fromGcode(path: Path): OrcaConfig {
            val m = LinkedHashMap<String, List<String>>()
            var inBlock = false
            Files.newBufferedReader(path).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("; CONFIG_BLOCK_START")) { inBlock = true; continue }
                    if (line.startsWith("; CONFIG_BLOCK_END")) break
                    if (!inBlock || !line.startsWith("; ")) continue
                    val eq = line.indexOf(" = ")
                    if (eq < 0) continue
                    val key = line.substring(2, eq).trim()
                    val raw = line.substring(eq + 3)
                    val plain = raw.none { it == ' ' || it == '"' || it == ';' } && !raw.contains("\\n")
                    m[key] = if (plain && raw.contains(',')) raw.split(',') else listOf(raw.replace("\\n", "\n"))
                }
            }
            require(m.isNotEmpty()) { "no CONFIG_BLOCK in $path" }
            return OrcaConfig(m)
        }

        /** Machine preset then process preset, later keys winning (QIDIStudio CLI's load order). */
        fun layered(vararg files: Path): OrcaConfig {
            val m = LinkedHashMap<String, List<String>>()
            for (f in files) {
                val obj = Json.parseToJsonElement(Files.readString(f)) as JsonObject
                for ((k, v) in obj) m[k] = when (v) {
                    is JsonArray -> v.map { it.jsonPrimitive.content }
                    is JsonPrimitive -> listOf(v.content)
                    else -> continue
                }
            }
            return OrcaConfig(m)
        }
    }
}
