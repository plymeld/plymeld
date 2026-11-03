package plymeld.core.decide

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Bond-first cooling (a quad helix's helices snapped mid-print): the bond factor, the cooling
 * shortfall, and the decision under the job's weights. The coefficients here are the bundled placeholders; what is tested
 * is the logic, not the numbers.
 */
class BondCoolingTest : StringSpec({
    val model = BondCooling.Model(fanLoss = 0.3, temperatureGain = 0.1, coolingLoss = 0.2, coolingTimeS = 10.0, enoughAreaMm2 = 50.0)
    val filament = BondCooling.Conditions(fanPct = 100.0, nozzleC = 210.0, minLayerTimeS = 6.0)
    val bond = BondCooling.Conditions(fanPct = 50.0, nozzleC = 220.0, minLayerTimeS = 3.0)

    "the bond factor: each term as the model says, a lower fan, a hotter nozzle and a shorter cooling each raising it" {
        // (1 - 0.3 x 1) x (1 + 0) x (1 - 0.2 x 0.6)
        BondCooling.factor(filament, 210.0, model) shouldBe (0.7 * 1.0 * 0.88 plusOrMinus 1e-12)
        // (1 - 0.3 x 0.5) x (1 + 0.1 x 1) x (1 - 0.2 x 0.3)
        BondCooling.factor(bond, 210.0, model) shouldBe (0.85 * 1.1 * 0.94 plusOrMinus 1e-12)
        (BondCooling.factor(filament.copy(fanPct = 50.0), 210.0, model) > BondCooling.factor(filament, 210.0, model)) shouldBe true
        (BondCooling.factor(filament.copy(nozzleC = 220.0), 210.0, model) > BondCooling.factor(filament, 210.0, model)) shouldBe true
        (BondCooling.factor(filament.copy(minLayerTimeS = 3.0), 210.0, model) > BondCooling.factor(filament, 210.0, model)) shouldBe true
        // control: a colder nozzle lowers it
        (BondCooling.factor(filament.copy(nozzleC = 200.0), 210.0, model) < BondCooling.factor(filament, 210.0, model)) shouldBe true
    }
    "the cooling shortfall: 0 with the filament's own cooling, the larger shortfall's share of the side area otherwise" {
        BondCooling.shortfallMm2(1000.0, filament, filament) shouldBe 0.0
        BondCooling.shortfallMm2(1000.0, filament, bond) shouldBe (500.0 plusOrMinus 1e-9)   // fan 50 % of 100, time 3 of 6: both a half
        BondCooling.shortfallMm2(1000.0, filament, filament.copy(fanPct = 80.0)) shouldBe (200.0 plusOrMinus 1e-9)
    }
    fun decide(weights: String, lock: Boolean? = null, area: Double? = 13.0) = BondCooling.decide("helix", filament, bond, 210.0, model, Weights.parse(weights, "test"),
        bondAreaMm2 = area, sideMm2 = 9000.0, surfaceMm2 = 10000.0, lock = lock, lockSource = if (lock != null) "plan.bond_cooling (test)" else null)

    "with no weight on strength bond-first never wins: it costs quality and gains nothing counted" {
        decide("quality=10,speed=1").value.on shouldBe false
    }
    "with strength weighed well over quality, bond-first wins" {
        decide("quality=1,strength=10").value.on shouldBe true
        // control: the same weights the other way round keep the filament's cooling
        decide("quality=10,strength=1").value.on shouldBe false
    }
    "a bond already strong enough gains nothing: the filament's cooling whatever the weights; unmeasured counts as enough" {
        // 100 mm²: 61.6 (the filament's) and 87.9 (bond-first) both above enough_area 50
        decide("quality=1,strength=10", area = 100.0).value.on shouldBe false
        decide("quality=1,strength=10", area = null).value.on shouldBe false
        BondCooling.strength(100.0, bond, 210.0, model) shouldBe 50.0
        // control: the same weights on a thin bond (13 mm², well under) choose bond-first
        decide("quality=1,strength=10", area = 13.0).value.on shouldBe true
    }
    "under equal weights a thin bond is worth the cooling given up on a mostly flat-topped part, not on an all-side one" {
        // side 9000 of 10000: the shortfall (0.45 linear) outweighs the factor's log ratio (ln 1.427 = 0.356)
        decide("quality=1,strength=1").value.on shouldBe false
        // side 1000 of 10000: the shortfall 0.05, bond-first wins
        BondCooling.decide("helix", filament, bond, 210.0, model, Weights.parse("quality=1,strength=1", "test"), 13.0, 1000.0, 10000.0, null, null).value.on shouldBe true
    }
    "a lock decides without weighing, either way" {
        val on = decide("quality=10", lock = true)
        on.value.on shouldBe true; on.overridden shouldBe true; on.value.conditions shouldBe bond
        decide("strength=10", lock = false).value.on shouldBe false
    }
})
