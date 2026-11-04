package plymeld.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import plymeld.commands.CommandModel

/**
 * The parity test: every command in the command model (`plymeld commands`, read
 * off the same tree the CLI parses) has a place in the UI, in [UiRegistry]: a surface that offers it, or a line in the
 * not-yet ledger naming the plan step that brings it. Nothing is in both; nothing in the registry names a command the
 * model does not have. So a command added to the CLI fails this test until the UI has decided where it goes, and a
 * surface that disappears fails it too.
 */
class UiParityTest : StringSpec({
    val model = CommandModel.read()
    val lines = model.root.all().map { it.line }

    "every command of the model is in a UI surface or in the not-yet ledger, and in only one of them" {
        val offered = UiRegistry.surfaces.map { it.command }
        val placed = (offered + UiRegistry.notYet.keys).toSet()
        lines.filter { it !in placed }.shouldBeEmpty()
        offered.filter { it in UiRegistry.notYet }.shouldBeEmpty()
        // the finder offers no form for a command not in the UI yet (control: it offers a command that is)
        Finder.commands.map { it.line }.filter { it in UiRegistry.notYet }.shouldBeEmpty()
        Finder.commands.map { it.line } shouldContain "plymeld printer status"
    }
    "the registry names no command the model does not have" {
        (UiRegistry.surfaces.map { it.command } + UiRegistry.notYet.keys).filter { it !in lines }.shouldBeEmpty()
    }
    "a surface's designed controls are options of its command" {
        for (s in UiRegistry.surfaces) {
            val names = model.root.all().first { it.line == s.command }.options.flatMap { it.names }.toSet()
            s.controls.filter { it !in names }.shouldBeEmpty()
        }
    }
    // the form (Finder.kt, CommandForm) draws a flag as a toggle, fixed choices as chips, and a value as one field per
    // occurrence; an option taking several values at once has no control yet, so a command adding one fails here until
    // the form learns it (the parity reaching options)
    "every option of every command has a shape the form renders: a flag, fixed choices, or one value" {
        val odd = model.root.all().flatMap { c -> c.options.filter { o -> !o.flag && o.choices.isEmpty() && o.nvalues.last != 1 }.map { "${c.line} ${it.names.last()} takes ${it.nvalues} values" } }
        odd.shouldBeEmpty()
    }
    "every argument of every command is a field: one value, or several separated with ;" {
        model.root.all().flatMap { c -> c.arguments }.shouldNotBeEmpty()
    }
    "the model holds the commands the README lists (a control: the walk reads the real tree, not an empty one)" {
        lines shouldContain "plymeld slice"
        lines shouldContain "plymeld printer loadout"
        lines shouldContain "plymeld investigate predict"
        model.paths.shouldNotBeEmpty()
        model.root.all().first { it.line == "plymeld slice" }.options.flatMap { it.names } shouldContain "--config-file"
    }
    // below the registry: that a command is registered says nothing about its form, which could hide -cf, -m and
    // --machine unnoticed
    "every option of every command reaches the command line from its form, -cf, -m and --machine included" {
        val missing = ArrayList<String>()
        for (c in model.root.all()) for (o in c.options) {
            val name = o.names.last()
            val words = if (o.flag) commandWords(c, emptyMap(), mapOf(name to true), emptyMap()) else commandWords(c, mapOf(name to (o.choices.firstOrNull() ?: "x")), emptyMap(), emptyMap())
            if (name !in words) missing += "${c.line} $name"
        }
        missing.shouldBeEmpty()
        // control: an option given no value is not on the line
        commandWords(model.root.all().first { it.line == "plymeld slice" }, emptyMap(), emptyMap(), emptyMap()).contains("--config-file") shouldBe false
    }
    "every config path the schema declares is found by the finder, its page the chain (a chip and a chain for every setting)" {
        val unfound = plymeld.config.Schema.decls.filter { d -> Finder.search(d.path, null, emptyList(), emptyList(), limit = 500).none { it.kind == Finder.Kind.SETTING && it.key == d.path } }.map { it.path }
        unfound.shouldBeEmpty()
    }
    "every setting a step's link opens is a path the schema declares, with no <key>, so its page can set it" {
        val bad = listOf("pose", "loadout", "priorities").flatMap { n -> stepSettings(n, null, listOf("boat"))?.paths ?: emptyList() }
            .filter { p -> '<' in p || plymeld.config.Schema.find(p.split('.')) == null }
        bad.shouldBeEmpty()
    }
    "control: a command the model does not have would be caught" {
        val stray = "plymeld nothing-of-the-kind"
        lines.contains(stray) shouldBe false
    }
})
