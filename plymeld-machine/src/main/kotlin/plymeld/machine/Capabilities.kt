package plymeld.machine

/**
 * What a printer model can do, as facts with evidence, read from its config
 * entry (`hardware.model.<key>`: the shipped ones bundled, a user's own beside them; plymeld-config's Models).
 * A shipped description never comes from another slicer's profile; it is built from evidence: the machine's firmware
 * config, commands its own firmware macros call, and what the machine's own slicer sends to it. A user's own may
 * carry no evidence beyond their config. Any machine whose model is described can be targeted.
 */
data class MachineDescription(
    /** The model's key, what a machine names it by (hardware.machine.<key>.model), e.g. `flashforge-c5`. */
    val id: String,
    val name: String,
    /** Other names the model goes by (another slicer's printer_model, read by the profile importer). */
    val otherNames: List<String>,
    /** The g-code dialect its files are written in unless a machine overrides it ([Dialect.id]). */
    val dialect: String,
    val commands: List<Command>,
    val fans: List<Fan> = emptyList(),
    val heaters: List<Heater> = emptyList(),
    val toolheads: List<Toolhead> = emptyList(),
    /** Motion limits (Klipper names: max_velocity, max_accel, max_z_velocity, max_z_accel, square_corner_velocity), each with evidence. */
    val motion: Map<String, MotionLimit> = emptyMap(),
    val notes: List<String> = emptyList(),
) {
    private val byWord = commands.associateBy { it.word.uppercase() }

    /** The command, if this machine accepts it (Klipper and Marlin command words are case-insensitive). */
    fun command(word: String): Command? = byWord[word.uppercase()]
    fun supports(word: String): Boolean = command(word) != null

    /** Toolhead [index] (0-based), or null when the description has none for it. */
    fun toolhead(index: Int): Toolhead? = toolheads.firstOrNull { it.index == index }

    /**
     * This description with toolhead [index]'s geometry values [values] (keys of [Toolhead.Tier], e.g. the TIP_CONE tier's
     * cone side angle from horizontal in degrees and how far the tip sits below the rest of the head in mm) set from the
     * user's config ([what], [from]: which tier, where). The maker publishes none of them for most machines; a config value
     * is the user's measurement.
     */
    fun withHead(index: Int, values: Map<String, Double>, what: String, from: String): MachineDescription {
        val old = toolhead(index)
        val head = Toolhead(index, (old?.geometry ?: emptyMap()) + values, (old?.evidence ?: emptyList()) + Evidence("config", "$what: $from"))
        return copy(toolheads = toolheads.filter { it.index != index } + head)
    }

    /** The fan plymeld commands for a role, or null when the machine has none. */
    fun fan(role: String): Fan? = fans.firstOrNull { it.role == role && it.command != null }
}

data class Command(val word: String, val purpose: String = "", val evidence: List<Evidence>)

/**
 * kind, strongest first: `tested` (printed on the machine, result recorded), `firmware_config`
 * (defined in the firmware config), `used_by_firmware` (a firmware macro calls it), `documented` (the firmware's own
 * documentation lists it, with the config section it needs), `observed`
 * (the machine's own slicer sends it), `stand_in` (weakest: a placeholder the user allowed until a
 * real value is known; `detail` says where it came from). `detail` names the file and line or count.
 */
data class Evidence(val kind: String, val detail: String) {
    init { require(kind in KINDS) { "unknown evidence kind '$kind'" } }
    /** `config`: a value set in the user's config, its provenance the config entry's (e.g. the user's measurement of the nozzle tip). */
    companion object { val KINDS = setOf("tested", "measured", "firmware_config", "used_by_firmware", "documented", "manufacturer_profile", "observed", "stand_in", "config") }
}

/**
 * A fan and what it is for: `part_cooling`, `auxiliary_part_cooling`, `chamber_circulation`,
 * `automatic` (the firmware runs it; plymeld never does), `unassigned`. [command] with `{s255}` for
 * the 0-255 speed, or null when plymeld has no evidence of how to drive it.
 */
data class Fan(val name: String, val role: String, val command: String? = null, val evidence: String)

/**
 * One toolhead and its OWN geometry: distances in mm from the nozzle tip. Each tier is
 * known only when all its values are:
 *   TIP_CONE     tip_cone_deg, head.nozzle.height
 *   UNDERSIDE    the loaded head seen from below as a rectangle: how far it reaches from the tip towards the machine's
 *                -X, +X, -Y, and +Y (head_minus_x, head_plus_x, head_minus_y, head_plus_y)
 *   CLEARANCE    head.radius (the head as a circle around the tip)
 *   GANTRY       gantry.height (and gantry.distance when known), lid.height
 */
data class Toolhead(val index: Int, val geometry: Map<String, Double>, val evidence: List<Evidence>,
                    /** How long (s) its nozzle takes to heat to print temperature: a tool's preheat when it sets none (hardware.tool.<key>.heat.time); null = not known. */
                    val heatS: Double? = null) {
    /** [settings]: what a tool or its model's toolhead sets to give the tier, for a message naming what is missing. */
    enum class Tier(val keys: List<String>, val settings: String) {
        TIP_CONE(listOf("tip_cone_deg", "head.nozzle.height"), "head.nozzle.angle and head.nozzle.height"),
        UNDERSIDE(listOf("head_minus_x", "head_plus_x", "head_minus_y", "head_plus_y"), "head.size.x / .y and head.nozzle.x / .y"),
        CLEARANCE(listOf("head.radius"), "head.radius"),
        GANTRY(listOf("gantry.height", "lid.height"), "gantry.height and lid.height"),
    }
    fun has(tier: Tier): Boolean = tier.keys.all { it in geometry }
    operator fun get(key: String): Double = geometry[key] ?: error("toolhead ${index + 1} has no '$key'")
}

data class MotionLimit(val value: Double, val evidence: Evidence)

data class Heater(val name: String, val section: String, val evidence: String, val maxTempC: Double? = null)

/** The printer models a config defines ([all]: the shipped ones and a user's own), by key. */
class ModelLibrary(val all: List<MachineDescription>) {
    /** By the model's key, or another name it goes by. A model with no description is refused, naming the ones there are. */
    fun forModel(model: String): MachineDescription = all.firstOrNull { it.id == model || model in it.otherNames }
        ?: error("no printer model '$model' (hardware.model.<key>: ${all.joinToString { it.id }.ifEmpty { "none" }}); a machine's model must be one plymeld ships or one described in your config")

    companion object {
        val EMPTY = ModelLibrary(emptyList())
    }
}
