package plymeld.config

import plymeld.machine.Command
import plymeld.machine.Evidence
import plymeld.machine.Fan
import plymeld.machine.Heater
import plymeld.machine.MachineDescription
import plymeld.machine.ModelLibrary
import plymeld.machine.MotionLimit
import plymeld.machine.Toolhead

/**
 * Printer models as config (`hardware.model.<key>`): the shipped ones in the bundled `03_models.toml`, a
 * user's own beside them, `like` taking over a base model's values (every command it accepts included). Each model is
 * read into a [MachineDescription], what the writer, the predictor, and the planner use.
 */
object Models {
    /** The shipped models alone (no config of the user's): for importers, tests, and the schema's suggestions. */
    val bundled: ModelLibrary by lazy { library(Config(ConfigStack(ConfigFiles.bundled()), emptyList())) }

    /** Every model [c] defines, shipped and the user's own. */
    fun library(c: Config): ModelLibrary = ModelLibrary(c.keysOf("model").map { describe(c, it) })

    /** The config's motion keys and the firmware's (Klipper's [printer]) names a description keeps them under. */
    private val LIMITS = mapOf("speed.max" to ("max_velocity" to Dimension.SPEED), "acceleration.max" to ("max_accel" to Dimension.ACCEL),
        "z.speed.max" to ("max_z_velocity" to Dimension.SPEED), "z.acceleration.max" to ("max_z_accel" to Dimension.ACCEL),
        "corner.speed" to ("square_corner_velocity" to Dimension.SPEED))
    /** The config's toolhead keys and the geometry keys a [Toolhead] uses for them. */
    private val GEOMETRY = linkedMapOf("head.nozzle.angle" to ("tip_cone_deg" to Dimension.ANGLE), "head.nozzle.height" to ("head.nozzle.height" to Dimension.LENGTH),
        "head.radius" to ("head.radius" to Dimension.LENGTH), "gantry.height" to ("gantry.height" to Dimension.LENGTH),
        "gantry.distance" to ("gantry.distance" to Dimension.LENGTH), "lid.height" to ("lid.height" to Dimension.LENGTH))

    /** Model [key] of [c] as a description: its own values and those it takes over through `like`. */
    fun describe(c: Config, key: String): MachineDescription {
        val base = listOf("hardware", "model", key)
        val mine = c.values.values.filter { it.path.size > 3 && it.path.subList(0, 3) == base }
        fun text(vararg rest: String): String? = c.entry(base + rest)?.value as? String
        fun under(vararg rest: String): Map<String, List<ConfigFiles.Entry>> =
            mine.filter { it.path.size > 3 + rest.size && it.path.subList(3, 3 + rest.size) == rest.toList() }.groupBy { it.path[3 + rest.size] }
        fun qty(e: ConfigFiles.Entry, dim: Dimension) = c.quantity(e, dim, Site())
        val commands = under("command").map { (word, es) -> command(word, es.single()) }.sortedBy { it.word }
        val motion = LIMITS.mapNotNull { (key, firmware) ->
            val (name, dim) = firmware
            c.entry(base + listOf("motion") + key.split('.'))?.let { e ->
                val ev = c.entry(base + listOf("evidence", "motion") + key.split('.'))?.takeIf { origin(it) == origin(e) }
                name to MotionLimit(qty(e, dim), evidence(ev?.value as? String, e).first())
            }
        }.toMap()
        val fans = under("fan").map { (name, es) ->
            fun v(k: String) = es.firstOrNull { it.path.last() == k }?.value as? String
            Fan(name, v("role") ?: "unassigned", v("command"), v("evidence") ?: es.first().source)
        }
        val heaters = under("heater").map { (name, es) ->
            fun e(k: String) = es.firstOrNull { it.path.drop(5).joinToString(".") == k }
            Heater(name, (e("section")?.value as? String) ?: name, (e("evidence")?.value as? String) ?: es.first().source,
                e("temperature.max")?.let { qty(it, Dimension.TEMPERATURE) })
        }
        val toolheads = under("toolhead").map { (index, es) ->
            val geometry = LinkedHashMap<String, Double>()
            fun at(k: String) = es.firstOrNull { it.path.drop(5).joinToString(".") == k }
            for ((k, g) in GEOMETRY) at(k)?.let { geometry[g.first] = qty(it, g.second) }
            // the underside as a tool gives it (its size, the nozzle's place in it), kept as the head's reach from the nozzle each way
            for (axis in listOf("x", "y")) {
                val size = at("head.size.$axis")?.let { qty(it, Dimension.LENGTH) }
                val nozzle = at("head.nozzle.$axis")?.let { qty(it, Dimension.LENGTH) }
                if ((size == null) != (nozzle == null))
                    error("hardware.model.$key.toolhead.$index: head.size.$axis and head.nozzle.$axis go together (the head's size and the nozzle's place in it); set both or neither")
                if (size != null && nozzle != null) {
                    if (nozzle < 0 || nozzle > size)
                        error("hardware.model.$key.toolhead.$index: the nozzle (head.nozzle.$axis $nozzle mm) is outside the head (head.size.$axis $size mm)")
                    geometry["head_minus_$axis"] = nozzle; geometry["head_plus_$axis"] = size - nozzle
                }
            }
            val ev = es.firstOrNull { it.path.last() == "evidence" }
            // the toolhead's evidence speaks for the values set beside it; one set elsewhere (your config over a bundled model) is the config's
            val elsewhere = if (ev == null) emptyList() else es.filter { it !== ev && origin(it) != origin(ev) }.map { Evidence("config", "${it.key} (${it.source})") }
            Toolhead(index.toIntOrNull() ?: error("hardware.model.$key.toolhead.$index: a toolhead is keyed by its index, from 0"),
                geometry, evidence(ev?.value as? String, ev ?: es.first()) + elsewhere)
        }.sortedBy { it.index }
        return MachineDescription(
            id = key,
            name = text("name") ?: key,
            otherNames = listOfNotNull(text("profile_name")?.takeIf { it.isNotBlank() }),
            dialect = text("dialect") ?: error("model '$key' has no dialect (hardware.model.$key.dialect, one of ${plymeld.machine.Dialect.entries.joinToString { it.id }})"),
            commands = commands,
            fans = fans,
            heaters = heaters,
            toolheads = toolheads,
            motion = motion,
            notes = text("notes")?.lines()?.filter { it.isNotBlank() } ?: emptyList(),
        )
    }

    /**
     * Where a value was set: its file (or other place) without the line, and without the `like` it was taken over through. A
     * value's evidence counts only when set in the same place: evidence in a bundled model says nothing about a number your
     * config puts in its place.
     */
    private fun origin(e: ConfigFiles.Entry): String = e.source.substringBefore(" via like ").replace(Regex(":\\d+(?= |$)"), "")   // "file:12 (in x.gcode)" too

    /** A command from its entry: its value's "purpose: ..." line, and its evidence lines. */
    private fun command(word: String, e: ConfigFiles.Entry): Command {
        val lines = (e.value as String).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val purpose = lines.firstOrNull { it.startsWith("purpose: ") }?.removePrefix("purpose: ") ?: ""
        return Command(word, purpose, evidence(lines.filterNot { it.startsWith("purpose: ") }.joinToString("\n"), e))
    }

    /**
     * Evidence lines "kind: detail"; a line without a known kind, and no line at all, count as the config's own, with the
     * entry's source as the detail where there is no text.
     */
    private fun evidence(text: String?, e: ConfigFiles.Entry): List<Evidence> {
        val lines = text?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (lines.isEmpty()) return listOf(Evidence("config", "${e.key} (${e.source})"))
        return lines.map { l ->
            val kind = l.substringBefore(": ", "")
            if (kind in Evidence.KINDS) Evidence(kind, l.substringAfter(": ")) else Evidence("config", l)
        }
    }
}
