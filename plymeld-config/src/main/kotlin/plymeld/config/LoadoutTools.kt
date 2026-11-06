package plymeld.config

import plymeld.model.Context
import plymeld.model.Resolver
import plymeld.model.Setup

/**
 * Which tool prints each part, read from the config as written: the pre-print check's reference for "every line printed
 * by the tool its part was assigned" (GcodeCheck, ModelCheck). A part's slot ([ConfigSetup.partSlot]: its own lock, else the
 * model file's number through `model.slots`), the machine's loadout slot with that number ([ConfigSetup.slots]),
 * and that slot's tool's `index`; as the g-code's T number, index - 1 (T0 is the tool of index 1). It reads the config, not
 * the pipeline's Setup or its program, so a tool lost on the way from one to the g-code shows.
 */
object LoadoutTools {
    /** Each plate object's part tools (0-based, in the model's part order), by the object's key. */
    fun byObject(c: Config): Map<String, List<Int>> {
        val (machine, slots) = loaded(c)
        return c.objects.associate { o ->
            o.key to o.partKeys.indices.map { k ->
                toolOf(slots, ConfigSetup.partSlot(c, o, k, Site(machine = machine)), machine, "part '${o.model.partNames[k]}' of '${o.key}'")
            }
        }
    }

    /**
     * The tools that print support or its interface where it is on (plan.support.slot / plan.support.interface.slot, a filament
     * number; 0 = whichever is loaded, which adds none), for any object of [setup].
     */
    fun supportTools(c: Config, setup: Setup): Set<Int> {
        val (machine, slots) = loaded(c)
        return setup.objects.indices.flatMap { o ->
            val ctx = Context(o)
            // the switch locked on names the slots; unset (decided per overhang) or off, the loadout adds none here
            if (Resolver.resolveOrNull(setup, "plan.support.enabled", ctx)?.value != true) emptyList()
            else listOf("plan.support.slot", "plan.support.interface.slot").mapNotNull { id ->
                (Resolver.resolve(setup, id, ctx).value as Number).toInt().takeIf { it > 0 }?.let { toolOf(slots, it, machine, id) }
            }
        }.toSet()
    }

    private fun loaded(c: Config): Pair<String, List<ConfigSetup.Slot>> {
        val machine = c.machine ?: throw ConfigError(listOf(c.machineProblem ?: "no machine"))
        val problems = ArrayList<String>()
        val slots = ConfigSetup.slots(c, machine, problems)
        if (problems.isNotEmpty()) throw ConfigError(problems)
        return machine to slots
    }

    private fun toolOf(slots: List<ConfigSetup.Slot>, n: Int, machine: String, what: String): Int =
        (slots.firstOrNull { it.number == n } ?: throw ConfigError(listOf(ConfigSetup.notLoaded(what, n, slots, machine)))).toolIndex - 1
}
