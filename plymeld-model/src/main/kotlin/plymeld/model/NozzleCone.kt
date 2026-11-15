package plymeld.model

/**
 * The nozzle's cone of one tool: the angle of its side from horizontal ([angleDeg]; a surface no steeper than it clears the
 * cone) and how far its tip sits below the rest of the loaded head ([heightMm]), each with where it came from. Each value is
 * the tool's own (hardware.tool.<key>.head.nozzle.angle / .height) when set, else the machine model's toolhead's for that
 * tool (hardware.model.<key>.toolhead.<index>.head.nozzle.angle / .height): a tool's value wins over its model's. Every
 * reader of the cone takes it this way: the curved techniques and islands, the pre-print check, support's order, and the
 * travel clearance.
 */
class NozzleCone(val angleDeg: Double, val heightMm: Double, val angleFrom: String, val heightFrom: String) {
    companion object {
        /** Where a user sets the cone, for a message naming what is missing. */
        const val WHERE = "hardware.tool.<key>.head.nozzle.angle and head.nozzle.height (the tool's), or " +
            "hardware.model.<key>.toolhead.<index>.head.nozzle.angle and head.nozzle.height (its machine model's)"
        /** The machine description's toolhead geometry keys the model's values are kept under (plymeld-config's Models). */
        const val ANGLE_KEY = "tip_cone_deg"
        const val HEIGHT_KEY = "head.nozzle.height"

        /** The cone of the tool printing [ctx]'s part (its object's own filament's without a part); null when a value is missing. */
        fun of(setup: Setup, ctx: Context, machine: plymeld.machine.MachineDescription? = machineOf(setup)): NozzleCone? {
            val a = angle(setup, ctx, machine) ?: return null
            val h = height(setup, ctx, machine) ?: return null
            return NozzleCone(a.first, h.first, a.second, h.second)
        }

        /** The angle (deg) of the tool printing [ctx]'s part, with where it came from; null when neither the tool nor its model gives it. */
        fun angle(setup: Setup, ctx: Context, machine: plymeld.machine.MachineDescription? = machineOf(setup)): Pair<Double, String>? =
            value(setup, ctx, "hardware.tool.head.nozzle.angle", ANGLE_KEY, "angle", machine)

        /** The height (mm) of the tool printing [ctx]'s part, with where it came from; null when neither the tool nor its model gives it. */
        fun height(setup: Setup, ctx: Context, machine: plymeld.machine.MachineDescription? = machineOf(setup)): Pair<Double, String>? =
            value(setup, ctx, "hardware.tool.head.nozzle.height", HEIGHT_KEY, "height", machine)

        /** The 0-based tool printing [ctx]'s part, or its object's own filament's without a part; null when the setup does not say. */
        fun toolOf(setup: Setup, ctx: Context): Int? {
            val o = setup.objects.getOrNull(ctx.obj) ?: return null
            val spool = ctx.part?.let { o.parts.getOrNull(it)?.spool } ?: o.spool
            return setup.spools.firstOrNull { it.id == spool }?.tool
        }

        /** The machine model the setup's machine names (hardware.machine.<key>.model), or null when its library does not describe it. */
        fun machineOf(setup: Setup): plymeld.machine.MachineDescription? {
            val id = (if (setup.objects.isEmpty()) null else Resolver.resolveOrNull(setup, "hardware.machine.model"))?.value as String? ?: return null
            return setup.models.all.firstOrNull { it.id == id || id in it.otherNames }
        }

        /** Model [machine]'s toolhead [tool]'s geometry value [key] (the cone's [what]), with where it came from. */
        fun fromModel(machine: plymeld.machine.MachineDescription?, tool: Int?, key: String, what: String): Pair<Double, String>? {
            val head = tool?.let { machine?.toolhead(it) } ?: return null
            val v = head.geometry[key] ?: return null
            return v to "its machine model's, hardware.model.${machine!!.id}.toolhead.${head.index}.head.nozzle.$what (" +
                head.evidence.joinToString("; ") { "${it.kind}: ${it.detail}" } + ")"
        }

        private fun value(setup: Setup, ctx: Context, id: String, key: String, what: String, machine: plymeld.machine.MachineDescription?): Pair<Double, String>? {
            Resolver.resolveOrNull(setup, id, ctx)?.let { return (it.value as Number).toDouble() to "hardware.tool.<key>.head.nozzle.$what: ${it.from}" }
            return fromModel(machine, toolOf(setup, ctx), key, what)
        }
    }
}
