package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** The line-preserving writer: a value replaced or added, everything else byte for byte as it was. */
class TomlEditTest : StringSpec({
    val plan = """
        # Written by `plymeld plan`.

        [plan]
        # together
        objects.printing = "together"

        [plan.object.3DBenchy-dual]
        # the pose
        pose.turn = "1 0 0 0 1 0 0 0 1"
        band.1.technique = "flat"
        band.1.heights = "follow"   # decided
        layer.infill.angle = "44.999 deg"

        # What the decisions were made from.
        [plan.source_fingerprint.model]
        3DBenchy-dual = "f3647296f993a5a2"
    """.trimIndent() + "\n"

    "replacing a value keeps the line's comment and every other line" {
        val c = TomlEdit.set(plan, "plan.object.3DBenchy-dual.band.1.heights", "one")
        c.before shouldBe "follow"
        c.line shouldBe 11
        c.text shouldContain "band.1.heights = \"one\"   # decided\n"
        c.text.replace("band.1.heights = \"one\"   # decided", "band.1.heights = \"follow\"   # decided") shouldBe plan
    }
    "a key with no line yet is added at the end of its table, before the comment that leads to the next one" {
        val c = TomlEdit.set(plan, "plan.object.3DBenchy-dual.band.1.layer.height", "0.12 mm")
        c.before shouldBe null
        c.text shouldContain "layer.infill.angle = \"44.999 deg\"\nband.1.layer.height = \"0.12 mm\"\n\n# What the decisions were made from."
        TomlEdit.get(c.text, "plan.object.3DBenchy-dual.band.1.layer.height") shouldBe "0.12 mm"
    }
    "the longest table wins: a plan.object key does not land under [plan]" {
        val c = TomlEdit.set(plan, "plan.object.3DBenchy-dual.technique.flat.layer.height", "0.12 mm")
        c.text shouldContain "layer.infill.angle = \"44.999 deg\"\ntechnique.flat.layer.height = \"0.12 mm\""
        c.text shouldNotContain "objects.printing = \"together\"\ntechnique"
    }
    "a path no table holds gets a new table at the end" {
        val c = TomlEdit.set("a = \"1\"\n", "hardware.machine.c5.motion.first_layer.speed", "100 mm/s")
        c.text shouldBe "a = \"1\"\n\n[hardware.machine.c5.motion.first_layer]\nspeed = \"100 mm/s\"\n"
        c.before shouldBe null
    }
    "a keep mark is part of the value; reading it back gives the marked value" {
        val c = TomlEdit.set(plan, "plan.object.3DBenchy-dual.pose.turn", "!1 0 0 0 1 0 0 0 1")
        TomlEdit.get(c.text, "plan.object.3DBenchy-dual.pose.turn") shouldBe "!1 0 0 0 1 0 0 0 1"
        c.before shouldBe "1 0 0 0 1 0 0 0 1"
    }
    "quoted key segments match bare ones; a value with a hash stays whole" {
        val text = "[plan.object.\"my boat\"]\n\"band\".1.technique = \"flat\"\ncolor = \"#FFA3B5\"   # pink\n"
        TomlEdit.get(text, "plan.object.my boat.band.1.technique") shouldBe "flat"
        TomlEdit.get(text, "plan.object.my boat.color") shouldBe "#FFA3B5"
        val c = TomlEdit.set(text, "plan.object.my boat.color", "#000000")
        c.text shouldContain "color = \"#000000\"   # pink\n"
    }
    "unset removes the line and the comment above it; unset under a prefix removes every key beneath" {
        val (t, removed) = TomlEdit.unset(plan, "plan.object.3DBenchy-dual.pose.turn")
        removed shouldBe listOf("plan.object.3DBenchy-dual.pose.turn")
        t shouldNotContain "pose.turn"
        t shouldNotContain "# the pose"
        t shouldContain "band.1.technique = \"flat\""
        val (u, gone) = TomlEdit.unset(plan, "plan.object.3DBenchy-dual.band", under = true)
        gone shouldBe listOf("plan.object.3DBenchy-dual.band.1.technique", "plan.object.3DBenchy-dual.band.1.heights")
        u shouldNotContain "band.1"
        u shouldContain "layer.infill.angle = \"44.999 deg\""
        // control: a path with no line removes nothing and changes nothing
        TomlEdit.unset(plan, "plan.object.3DBenchy-dual.nothing").let { (x, r) -> x shouldBe plan; r shouldBe emptyList() }
    }
    "control: an unchanged file reads the value back and set on the same value leaves the text equal" {
        val c = TomlEdit.set(plan, "plan.objects.printing", "together")
        c.text shouldBe plan
        c.before shouldBe "together"
    }

    // a note's lines filled: no line but its last could take the next line's first word within the width
    fun filled(note: List<String>) = note.zipWithNext().all { (a, b) -> a.length + 1 + b.trimStart('#', ' ').substringBefore(' ').length > TomlEdit.COMMENT_WIDTH }

    "a note broken across lines is joined, then wrapped filled; its items and the notes after it stay their own" {
        val clean = "# Clean: hidden (--clean hidden). Per band, the fastest speed clean with every slower one in both directions, credited at most what a"
        val lines = listOf(clean, "# 29.6 mm face reaches at the band's acceleration (sqrt(a x L)):", "#   band 1, 310 mm/s2: along X to 169 mm/s", "#   band 2, 630 mm/s2: along X to 140 mm/s",
            "# A row per band, a column per speed. `plymeld calibrate motion values`", "# refuses a sheet with a \"?\" left.", "# The faces: along X 1, 3; along Y 2, 4.", "key = \"1\"")
        val out = TomlEdit.wrapComments(lines)
        val note = out.takeWhile { !it.startsWith("#   ") }
        note.all { it.startsWith("# ") && it.length <= TomlEdit.COMMENT_WIDTH } shouldBe true
        filled(note) shouldBe true
        note.joinToString(" ") { it.removePrefix("# ") } shouldBe clean.removePrefix("# ") + " 29.6 mm face reaches at the band's acceleration (sqrt(a x L)):"
        // the items as they were; the next note joined to its sentence's end; a note that ends its sentence on its own line kept
        out.filter { it.startsWith("#   ") } shouldBe lines.subList(2, 4)
        out shouldContainInOrder listOf("# A row per band, a column per speed. `plymeld calibrate motion values` refuses a sheet with a \"?\" left.", "# The faces: along X 1, 3; along Y 2, 4.", "key = \"1\"")
        // control: the note wrapped line by line, as before, is caught, its first line's last word left on a line of its own
        val lineByLine = listOf("# Clean: hidden (--clean hidden). Per band, the fastest speed clean with every slower one in both directions, credited", "#   at most what a",
            "# 29.6 mm face reaches at the band's acceleration (sqrt(a x L)):")
        filled(lineByLine) shouldBe false
    }
})
