package plymeld.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import plymeld.model.Catalog
import plymeld.model.Kind

/** The schema's declarations against the pipeline's settings. */
class SchemaTest : StringSpec({
    "every path is declared once" {
        Schema.decls.groupBy { it.path }.filterValues { it.size > 1 }.keys.shouldBeEmpty()
    }
    "every setting a path feeds exists, and its kind fits the path's type" {
        val fits = mapOf(
            Dimension.LENGTH to setOf(Kind.LENGTH), Dimension.AREA to setOf(Kind.AREA), Dimension.VOLUME to setOf(Kind.VOLUME),
            Dimension.TIME to setOf(Kind.TIME), Dimension.SPEED to setOf(Kind.SPEED), Dimension.ACCEL to setOf(Kind.ACCEL),
            Dimension.FLOW to setOf(Kind.FLOW), Dimension.ANGLE to setOf(Kind.ANGLE), Dimension.TEMPERATURE to setOf(Kind.TEMP),
            Dimension.RATIO to setOf(Kind.FRACTION), Dimension.COUNT to setOf(Kind.COUNT), Dimension.DENSITY to setOf(Kind.DENSITY))
        val bad = Schema.decls.filter { it.feeds != null }.mapNotNull { d ->
            val s = runCatching { Catalog[d.feeds!!] }.getOrNull() ?: return@mapNotNull "${d.path}: no setting ${d.feeds}"
            val ok = when (val t = d.type) {
                is Schema.Type.Quantity -> s.kind in fits.getValue(t.dim)
                is Schema.Type.Bool -> s.kind == Kind.BOOL
                is Schema.Type.Choice -> s.kind == Kind.CHOICE || s.kind == Kind.TEXT
                is Schema.Type.Text -> s.kind == Kind.TEXT
                is Schema.Type.Ref -> s.kind == Kind.TEXT   // the setting holds the referenced entry's key
                else -> false
            }
            if (ok) null else "${d.path} (${d.type.label}) feeds ${d.feeds} (${s.kind})"
        }
        bad.shouldBeEmpty()
    }
    "paths match with keys and scopes; control: an undeclared path does not" {
        Schema.find("hardware.filament.pla-white.temperature.print".split('.'))?.feeds shouldBe "hardware.filament.temperature.print"
        Schema.find("plan.object.benchy.technique.flat.layer.height".split('.'))?.feeds shouldBe "plan.technique.flat.layer.height"
        Schema.find("plan.object.benchy.part.hull.technique.flat.layer.height".split('.'))?.feeds shouldBe "plan.technique.flat.layer.height"
        Schema.find("plan.object.benchy.band.1.technique".split('.')) shouldNotBe null
        Schema.find("hardware.filament.pla-white.temperature.nozzle".split('.')) shouldBe null
        Schema.find("plan.technique.flat.layer.height.extra".split('.')) shouldBe null
    }
    "flat layers' largest stair-step feeds plan.technique.flat.step.max; control: the old id layers.adaptive.step.max is unknown" {
        Schema.find("plan.technique.flat.step.max".split('.'))?.feeds shouldBe "plan.technique.flat.step.max"
        Catalog["plan.technique.flat.step.max"].kind shouldBe Kind.LENGTH
        shouldThrow<IllegalStateException> { Catalog["layers.adaptive.step.max"] }.message!! shouldContain "unknown setting"
        Catalog.settings.none { "adaptive" in it.id } shouldBe true
    }

    // ids are exposed, so an id is the config path that sets it, without its <key>s; a setting no one path sets is
    // listed (Schema.made / unkeyed) and named in a group of config paths
    "every catalog id follows the config keys' naming; controls: ids that break it are each reported" {
        val ids = Catalog.settings.map { it.id }
        Schema.conventionProblems(ids).shouldBeEmpty()
        // every setting is accounted for: fed by a path, made from paths, or listed as having none
        val fed = Schema.decls.mapNotNull { it.feeds }.toSet()
        ids.filter { it !in fed && it !in Schema.made && it !in Schema.unkeyed }.shouldBeEmpty()
        // controls: an old-style id no path feeds, an id one segment off its path, one in a group no path has, and a listed id
        // that is no setting
        Schema.conventionProblems(ids + "walls.count").single() shouldContain "walls.count: no config path feeds it"
        Schema.conventionProblems(ids + "plan.layer.walls.outer_line_widht").single() shouldContain "no config path feeds it"
        Schema.conventionProblems(ids - "plan.layer.walls.count").single() shouldContain "plan.layer.walls.count: listed in Schema.made"
        Schema.conventionProblems(ids + "support.gap.side", unkeyed = Schema.unkeyed + ("support.gap.side" to "test")).single() shouldContain
            "support.gap.side: not in a group of config paths"
        Schema.conventionProblems(ids, made = Schema.made + ("plan.layer.walls.count" to listOf("plan.layer.walls.thikness"))).single() shouldContain
            "made from 'plan.layer.walls.thikness', which is not a declared path"
        Schema.conventionProblems(ids + "plan.faces.flat_top.technique", made = Schema.made + ("plan.faces.flat_top.technique" to listOf("plan.faces.side.technique"))).single() shouldContain
            "is the config path plan.faces.flat_top.technique, which does not set it"
    }
    "a fed id is its path without <key>s, by construction" {
        Schema.idOf("hardware.tool.<key>.layer.height.min") shouldBe "hardware.tool.layer.height.min"
        Schema.idOf("hardware.filament.<key>.bed.<key>.temperature.print") shouldBe "hardware.filament.bed.temperature.print"
        Schema.find("hardware.tool.t1.layer.height.min".split('.'))?.feeds shouldBe "hardware.tool.layer.height.min"
        Schema.find("hardware.machine.q2.model".split('.'))?.feeds shouldBe "hardware.machine.model"
        // control: a path that feeds nothing has no id
        Schema.find("hardware.machine.q2.build.x".split('.'))?.feeds shouldBe null
    }
    "the config paths that set a setting, for messages; control: one no path sets has none" {
        Schema.keysOf("hardware.machine.model") shouldBe "hardware.machine.<key>.model"
        Schema.keysOf("hardware.machine.build.left") shouldBe "hardware.machine.<key>.build.x / hardware.machine.<key>.build.origin"
        Schema.keysOf("plan.layer.infill.anchor_length.max") shouldBe null
        // (support's gaps have paths)
        Schema.keysOf("plan.support.gap.side") shouldBe "plan.support.gap.side"
    }
    // the naming rules, checked on every key the schema declares (and every id no one key sets)
    "every config key follows the naming rules; controls: a key breaking each rule is reported" {
        Schema.namingProblems().shouldBeEmpty()
        fun bad(path: String, type: Schema.Type) = Schema.namingProblems(Schema.decls + Schema.Decl(path, type, "test"))
        fun q(path: String, dim: Dimension) = bad(path, Schema.Type.Quantity(dim))
        // one control per rule: each is the shape a key had before the rules, or a plain typo of the rule
        q("hardware.machine.<key>.motion.print_velocity", Dimension.SPEED).joinToString() shouldContain "'velocity' is retired: write speed"
        q("plan.mechanism.motion.visible_accel", Dimension.ACCEL).joinToString() shouldContain "'accel' is retired"
        q("hardware.machine.<key>.motion.Fast", Dimension.SPEED).joinToString() shouldContain "is not lower-case words joined by _"
        q("plan.mechanism.motion.bridge_rate", Dimension.SPEED).joinToString() shouldContain "a speed ends with 'speed'"
        q("hardware.tool.<key>.hot", Dimension.TEMPERATURE).joinToString() shouldContain "a temperature ends with 'temperature'"
        q("method.search.baseline.layer.height", Dimension.RATIO).joinToString() shouldContain "'height' names a length"
        q("hardware.tool.<key>.max_layer_height", Dimension.LENGTH).joinToString() shouldContain "min / max are a leaf"
        q("job.requirements.time_max", Dimension.TIME).joinToString() shouldContain "min / max are a leaf"
        q("hardware.tool.<key>.layer.max.height", Dimension.LENGTH).joinToString() shouldContain "'max' is not the last segment"
        q("hardware.machine.<key>.motion.max", Dimension.SPEED).joinToString() shouldContain "a speed ends with 'speed'"
        q("plan.layer.infill.infill_width", Dimension.LENGTH).joinToString() shouldContain "a line's width is line.width"
        q("plan.technique.flat.layer_height", Dimension.LENGTH).joinToString() shouldContain "a layer's height is layer.height"
        q("plan.mechanism.motion.travel_speed", Dimension.SPEED).joinToString() shouldContain "a thing's speed or acceleration is <thing>.speed"
        q("plan.mechanism.purge.tower_x", Dimension.LENGTH).joinToString() shouldContain "an axis is a .x / .y / .z member"
        bad("plan.mechanism.purge.tower", Schema.Type.Bool).joinToString() shouldContain "is plan.mechanism.purge.tower.enabled beside them"
        bad("job.requests.texture", Schema.Type.Choice(listOf("none", "fuzzy"))).joinToString() shouldContain "a choice has no \"none\""
        q("model.object.<key>.part.<key>.filament", Dimension.COUNT).joinToString() shouldContain "'filament' holds a filament's key"
        bad("plan.support.slot", Schema.Type.Ref("filament")).joinToString() shouldContain "'slot' holds a slot's number"
        q("hardware.filament.<key>.speed.first_layer", Dimension.SPEED).joinToString() shouldContain "the same words in two shapes"
        // an id no key sets is checked too
        Schema.namingProblems(ids = Schema.made.keys + "hardware.machine.build.min_x").joinToString() shouldContain "min / max are a leaf"
    }

})
