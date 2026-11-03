package plymeld.core.decide

/**
 * One determined unknown: the value, why, the facts it was derived
 * from, the alternatives it beat, the stand-in rules it relied on (rules used where no real model
 * exists yet), and whether an override locked it. Printed by `plymeld plan`.
 */
class Decision<T>(
    val step: String,
    val value: T,
    val why: String,
    val facts: List<String>,
    val alternatives: List<Alternative> = emptyList(),
    val standIns: List<String> = emptyList(),
    val overridden: Boolean = false,
    /** What was decided, whatever the step's name says (the plan is read by kind, never by position). */
    val kind: Kind = Kind.of(step),
    /** Wall-clock seconds the decision took, where it was timed (item 6: `plan` says where its time goes). */
    val seconds: Double? = null,
) {
    /** The kinds of decision plymeld makes; [OTHER] for a step with none of their names. */
    enum class Kind(val label: String) {
        POSE("pose"), TECHNIQUE("technique"), BOND_COOLING("bond-first cooling"), SEAMS("seams"), RETRACTION("retraction in islands"), OBJECT_ORDER("object order"), OTHER("other");
        companion object {
            fun of(step: String): Kind = entries.firstOrNull { it != OTHER && step.startsWith(it.label) } ?: OTHER
        }
    }

    /** The same decision with the time it took. */
    fun took(seconds: Double) = Decision(step, value, why, facts, alternatives, standIns, overridden, kind, seconds)

    fun describe(): String = buildString {
        append("[$step]${seconds?.let { " (%.1f s)".format(java.util.Locale.ROOT, it) } ?: ""} ${if (overridden) "LOCKED BY OVERRIDE: " else ""}$why\n")
        for (f in facts) append("    fact: $f\n")
        for (a in alternatives) append("    ${if (a.chosen) "*" else " "} ${a.label}: ${a.score}${a.rejected?.let { " -- rejected: $it" } ?: ""}\n")
        for (s in standIns) append("    stand-in: $s\n")
    }
}

/** The same decision under another step name (one pose decision per object). */
fun <T> Decision<T>.renamed(step: String) = Decision(step, value, why, facts, alternatives, standIns, overridden, kind, seconds)

class Alternative(val label: String, val score: String, val rejected: String? = null, val chosen: Boolean = false)
