package plymeld.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Settings read under their new names ([Migrations]): an old name in a file, under an object's or a part's scope too,
 * reads as its new one with its value converted and a warning naming both; an old name and a new one layer by their
 * sources' order. Controls: a path that only starts like an old one, and every other path, read as written, with no
 * warning. And the table itself: every new name is a setting plymeld knows, and no old name is (a change must never
 * shadow a live setting).
 */
class MigrationsTest : StringSpec({
    fun entry(path: String, value: Any, keep: Boolean = false) = ConfigFiles.Entry(path.split('.'), value, "test.toml:1", keep)

    "an old name reads as its new one, its value converted, its source and keep mark kept, with a note naming both" {
        val on = Migrations.migrate(entry("plan.layer.walls.inner_first", "true", keep = true))
        val off = Migrations.migrate(entry("plan.layer.walls.inner_first", "false"))
        val e = on.entry!!
        e.key shouldBe "plan.layer.walls.order"
        e.value shouldBe "inner_first"
        e.keep shouldBe true
        e.source shouldBe "test.toml:1"
        off.entry!!.value shouldBe "outer_first"
        val note = on.note!!
        note shouldContain "plan.layer.walls.inner_first (test.toml:1): renamed after plymeld 0.20261109.1"
        note shouldContain "read as plan.layer.walls.order = \"inner_first\""
        note shouldContain "plymeld migrate"
        // a value plymeld supplied (bundled, or a g-code's block): no advice to rewrite a file
        Migrations.migrate(ConfigFiles.Entry(listOf("plan", "layer", "walls", "inner_first"), "false", "bundled 00_defaults.toml:52 (in a.gcode)")).note!!.contains("plymeld migrate") shouldBe false
    }

    "under an object's and a part's scope, the scope kept" {
        Migrations.migrate(entry("plan.object.box.layer.walls.inner_first", "true")).entry!!.key shouldBe "plan.object.box.layer.walls.order"
        Migrations.migrate(entry("plan.object.box.part.lid.layer.walls.inner_first", "false")).entry!!.key shouldBe "plan.object.box.part.lid.layer.walls.order"
    }

    "controls: a path that only starts like an old one, and any other path, read as written with no note" {
        for (p in listOf("plan.layer.walls.inner_first_x", "plan.layer.walls.order", "plan.layer.walls", "hardware.filament.pla.flow", "plan.object.box.layer.walls.style")) {
            val e = entry(p, "1")
            val r = Migrations.migrate(e)
            (r.entry === e) shouldBe true
            r.note shouldBe null
        }
    }

    "in a config: the old name's value in force under the new name, the note a warning; old and new layer by their order" {
        fun config(vararg es: ConfigFiles.Entry) = Config(ConfigStack(ConfigFiles.bundled() + es.toList()), emptyList())
        val old = config(entry("plan.layer.walls.inner_first", "true"))
        old.values["plan.layer.walls.order"]!!.value shouldBe "inner_first"
        old.values.containsKey("plan.layer.walls.inner_first") shouldBe false
        old.warnings.filter { "renamed after plymeld" in it }.size shouldBe 1
        // the later source wins, whichever name it uses
        config(entry("plan.layer.walls.order", "inner_outer_inner"), entry("plan.layer.walls.inner_first", "false")).values["plan.layer.walls.order"]!!.value shouldBe "outer_first"
        config(entry("plan.layer.walls.inner_first", "false"), entry("plan.layer.walls.order", "inner_outer_inner")).values["plan.layer.walls.order"]!!.value shouldBe "inner_outer_inner"
        // control: the new name alone, no warning
        config(entry("plan.layer.walls.order", "inner_first")).warnings.filter { "renamed after plymeld" in it }.shouldBeEmpty()
    }

    "the table: every new name a setting plymeld knows, no old name one (a change never shadows a live setting)" {
        for (c in Migrations.CHANGES) {
            (Schema.find(c.oldSegments.map { if (it == "<key>") "k" else it }) == null) shouldBe true
            if (c is Migrations.Renamed) (Schema.find(c.new.split('.').map { if (it == "<key>") "k" else it }) != null) shouldBe true
        }
    }
})
