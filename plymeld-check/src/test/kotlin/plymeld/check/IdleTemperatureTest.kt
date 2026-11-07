package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The bundled idle temperature of a parked nozzle (multi-nozzle machines): every bundled kind parks 100 C below printing.
 * A filament of the user's own, made like one of them, keeps its own idle temperature.
 */
class IdleTemperatureTest : StringSpec({
    fun drop(filament: String, vararg extra: Pair<String, Any>): Double {
        val setup = testSetup("hardware.machine.tm.loadout.tool.t1.filament" to filament, *extra)
        return (plymeld.model.Resolver.resolveOrNull(setup, "hardware.filament.temperature.idle_drop", plymeld.model.Context(0, 0))!!.value as Number).toDouble()
    }
    "bundled PLA and PETG park 100 C below printing; control: a filament's own idle temperature is kept" {
        drop("pla-generic") shouldBe 100.0
        drop("petg-generic") shouldBe 100.0
        drop("mine", "hardware.filament.mine.like" to "pla-generic", "hardware.filament.mine.temperature.idle" to "150 C") shouldBe 60.0
    }
})
