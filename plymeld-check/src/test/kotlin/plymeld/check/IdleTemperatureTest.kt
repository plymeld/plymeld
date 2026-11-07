package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The bundled idle temperatures of a parked nozzle (multi-nozzle machines): PLA's is 120 C, so the bundled PLA (210 C to
 * print) parks 90 C below printing; a kind with its own rule (PETG: print - 40 C) keeps it.
 */
class IdleTemperatureTest : StringSpec({
    fun drop(filament: String): Number? {
        val setup = testSetup("hardware.machine.tm.loadout.tool.t1.filament" to filament)
        return plymeld.model.Resolver.resolveOrNull(setup, "hardware.filament.temperature.idle_drop", plymeld.model.Context(0, 0))?.value as Number?
    }
    "bundled PLA parks at 120 C: 90 C below its 210 C; control: bundled PETG still 40 C below its print temperature" {
        drop("pla-generic")!!.toDouble() shouldBe 90.0
        drop("petg-generic")!!.toDouble() shouldBe 40.0
    }
})
