package plymeld.core.check

import plymeld.model.Setup

/**
 * Nozzle temperatures against the filament's range (the spool's label: hardware.filament.temperature.min / _max) and the
 * tool's limit (hardware.tool.temperature.max): warnings, not refusals.
 */
object TemperatureLimits {
    private fun num(v: plymeld.model.Sourced?) = (v?.value as Number?)?.toDouble()

    /** Warnings for temperatures [temps] (C) printed with [spool]'s filament in its tool. */
    fun warnings(setup: Setup, spool: String, temps: Collection<Int>, what: String): List<String> {
        val sp = setup.spool(spool)
        val mt = setup.materialTool[sp.material to sp.tool]
        val lo = num(mt?.get("hardware.filament.temperature.min")); val hi = num(mt?.get("hardware.filament.temperature.max"))
        val max = num(setup.tools.getOrNull(sp.tool)?.get("hardware.tool.temperature.max"))
        val out = ArrayList<String>()
        val below = temps.filter { lo != null && it < lo }; val above = temps.filter { hi != null && it > hi }
        if (below.isNotEmpty()) out += "$what: ${below.sorted().joinToString { "$it C" }} below ${sp.material}'s range (${lo!!.toInt()}-${hi?.toInt() ?: "?"} C, hardware.filament.<key>.temperature.min): it may not melt or bond well"
        if (above.isNotEmpty()) out += "$what: ${above.sorted().joinToString { "$it C" }} above ${sp.material}'s range (${lo?.toInt() ?: "?"}-${hi!!.toInt()} C, hardware.filament.<key>.temperature.max): it may degrade, ooze, or string"
        val over = temps.filter { max != null && it > max }
        if (over.isNotEmpty()) out += "$what: ${over.sorted().joinToString { "$it C" }} above tool ${sp.tool + 1}'s limit of ${max!!.toInt()} C (hardware.tool.<key>.temperature.max): check the hotend can take it before printing"
        if (lo == null || hi == null) out += "$what: ${sp.material} has no temperature range (temperature.min / temperature.max), so its temperatures are not checked against one"
        return out
    }

    /** Warnings for every spool the print uses (its objects' and parts'), at its own print and first-layer temperatures. */
    fun warnings(setup: Setup): List<String> {
        val used = setup.objects.flatMap { o -> listOf(o.spool) + o.parts.map { it.spool } }.toSet()
        return setup.spools.filter { it.id in used }.flatMap { sp ->
            val mt = setup.materialTool[sp.material to sp.tool]
            val temps = listOfNotNull(num(mt?.get("hardware.filament.temperature.print")), num(mt?.get("hardware.filament.temperature.first_layer"))).map { it.toInt() }
            if (temps.isEmpty()) emptyList() else warnings(setup, sp.id, temps, "${sp.material} in tool ${sp.tool + 1}")
        }
    }
}
