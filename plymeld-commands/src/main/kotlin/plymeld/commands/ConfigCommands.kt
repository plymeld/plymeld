package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import plymeld.config.ConfigFiles
import plymeld.config.ConfigStack

/**
 * The inputs every product command takes: config files and globs, single config values, model files and globs. Nothing
 * else: a print's settings never come from a model or another slicer's file.
 */
abstract class ConfigCommand(name: String) : CliktCommand(name = name) {
    protected val configFiles by option("-cf", "--config-file", metavar = "FILE|GLOB",
        help = "a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)").multiple()
    protected val configValues by option("-cv", "--config-value", metavar = "PATH=VALUE",
        help = "one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file").multiple()
    protected val machineOpt by option("--machine", metavar = "KEY",
        help = "the machine that prints (hardware.machine.<key>; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines")

    /**
     * The config files: `-cf` as given, else every `.toml` file in plymeld's config folder (said, with the folder). An empty
     * folder in a terminal starts the first-use setup ([InitFlow]) first; elsewhere it says to run `plymeld init`.
     */
    protected fun files(): List<String> = configFiles.ifEmpty {
        val dir = plymeld.config.UserDirs.current().config
        if (ConfigFiles.defaultFiles().isEmpty()) {
            val flow = InitFlow(terminal, ::echo)
            if (flow.canAsk) { echo("config: no -cf, and no .toml file in the config folder $dir: setting up first"); flow.run(dir) }
        }
        ConfigFiles.defaultFiles().map { it.toString() }.also { fs ->
            echo(if (fs.isEmpty()) "config: no -cf, and no .toml file in the config folder $dir: plymeld's bundled values only (plymeld init sets up your printer and filaments)"
                 else "config: no -cf: the ${fs.size} .toml file(s) in the config folder $dir")
        }
    }

    protected fun stack(): ConfigStack = try { ConfigStack.load(files(), configValues) } catch (e: plymeld.config.ConfigError) {
        throw UsageError("the config has ${e.problems.size} problem(s):\n" + e.problems.joinToString("\n") { "  $it" })
    }
}

/** `--slot N` as the config value it sets: the slot map's every-number entry (`model.slots."*" = N`), from the option. */
fun slotEntry(n: Int): ConfigFiles.Entry {
    if (n < 1) throw UsageError("--slot is a slot's number, from 1 (got $n)")
    return ConfigFiles.Entry(listOf("model", "slots", "*"), n.toLong(), "--slot")
}

/** The model files of a product command: `--model` / `-m`, repeatable, globs in natural order. */
abstract class ModelConfigCommand(name: String) : ConfigCommand(name) {
    protected val modelArgs by option("-m", "--model", metavar = "FILE|GLOB",
        help = "a model file (STL, 3MF) or a glob of them; repeatable; each adds its objects to the plate").multiple(required = true)

    // how each object's techniques were chosen; it changes nothing decided or printed
    protected val explain by option("--explain",
        help = "also print how each object's techniques were chosen: the proposals and their fate, the candidates and their measures, the handoffs, the scoring, and the decision").flag()
    protected val explainJson by option("--explain-json", metavar = "FILE", help = "write the same as --explain as JSON to FILE").path()

    /** `--explain` (text) and `--explain-json` (the same as JSON) for [plan]. */
    protected fun explainOut(plan: plymeld.core.decide.Plan) {
        if (explain) plan.explains.forEach { echo(it.text().trimEnd()) }
        explainJson?.let { out ->
            out.toAbsolutePath().parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(out, plymeld.core.decide.TechniqueExplain.json(linkedMapOf(
                "about" to "how each object's techniques were chosen",
                "objects" to plan.explains.map { it.tree() })) + "\n")
            echo("explain written to $out")
        }
    }

    // a 3MF project with objects on several plates is refused until one is named (the refusal said "choose one with
    // --plate", which only `investigate slice` had)
    protected val plateOpt by option("--plate", metavar = "N", help = "which plate of a 3MF project to use (1-based, as the project numbers them); needed when several have objects").int()

    // a multi-color model printed from one filament, without editing it: the slot map's every-number entry for this run
    protected val slotOpt by option("--slot", metavar = "N",
        help = "print every part from slot N of the loadout, whatever filament the model file puts it on (sets model.slots.\"*\" = N for this run, over the config; model.slots maps the model's filaments one by one)").int()

    /** [slotOpt] as the config value it sets, after every -cv value. */
    protected fun slotEntries(): List<ConfigFiles.Entry> = slotOpt?.let { listOf(slotEntry(it)) }.orEmpty()

    protected fun models(): List<java.nio.file.Path> = ConfigFiles.expand(modelArgs)

    protected fun product(rePlan: Boolean = false): Product = Product(ConfigStack(stack().all + slotEntries()), models(), machineOpt, plateOpt, rePlan) { echo(it) }
}

/**
 * `plymeld slice -cf ... -m ... -o OUT.gcode`. A plan in the config (`plymeld plan -o`) is used: when
 * complete, nothing is decided again; with none, plymeld decides and says so.
 */
class SliceCommand : ModelConfigCommand("slice") {
    private val output by option("-o", "--output", metavar = "FILE", help = "g-code file to write").path().required()
    private val from by option("--from", metavar = "GCODE",
        help = "start from what a g-code `plymeld slice -o` wrote was made from: the config and plan in its source block (-cf and -cv add to them; -m names the model files)").path()
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Slice models (-m) to g-code with config files (-cf) and values (-cv)."

    override fun run() {
        // the work is SliceSession's (the window runs the same two halves, the check in the background)
        val session = SliceSession()
        val w = session.write(from?.let(::fromGcode) ?: product(), output, ::echo)
        explainOut(w.plan)
        session.check(w, ::echo).refusal?.let { error(it) }
    }

    /**
     * The job a g-code's source block records: plymeld's bundled files, the block's config (its plymeld values still plymeld's,
     * so they take the place of this version's and give way as they did), its plan, then -cf and -cv. Said when this
     * plymeld is another version or a model file is not the one recorded.
     */
    private fun fromGcode(g: java.nio.file.Path): Product {
        val src = SourceBlock.read(java.nio.file.Files.readString(g), g.fileName.toString())
        if (src.version != PlymeldVersion.value)
            echo("warning: ${g.fileName} was made by plymeld ${src.version}, this is ${PlymeldVersion.value}: the same config may be laid out differently")
        val given = models()
        for (r in src.models) {
            val m = given.firstOrNull { it.fileName.toString() == r.name }
            if (m == null) { echo("warning: ${g.fileName} was made from model '${r.name}', not given (-m)"); continue }
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(m)).joinToString("") { "%02x".format(it) }
            if (sha != r.sha256) echo("warning: model '${r.name}' is not the file ${g.fileName} was made from (its SHA-256 differs)")
        }
        val stack = try {
            ConfigStack(ConfigFiles.bundled() + src.entries + src.plan + ConfigFiles.readFiles(configFiles) + configValues.map { ConfigFiles.value(it) } + slotEntries())
        } catch (e: plymeld.config.ConfigError) {
            throw UsageError("the config has ${e.problems.size} problem(s):\n" + e.problems.joinToString("\n") { "  $it" })
        }
        return Product(stack, given, machineOpt, plateOpt, false) { echo(it) }
    }
}

/**
 * The g-code [text] of a lone object read in its curved band's warped space (CurvedCheck), or null when it has none. The
 * warp is the check's own, from the model. A plate of several objects is PlateCheck's, each object in its own warp.
 */
internal fun curvedRead(text: String, plan: plymeld.core.decide.Plan, setup: plymeld.model.Setup, zBase: Double): plymeld.core.check.CurvedCheck.Read? {
    require(plan.layout.placed.size == 1) { "a plate of ${plan.layout.placed.size} objects is read object by object (PlateCheck)" }
    // curved islands: each found again on the model by the plan's lock (its point, start, and layer height), placed with it
    val locked = plan.layout.techniques[0].islands
    if (locked.isNotEmpty()) {
        val ctx = plymeld.model.Context(plan.layout.objectIndex[0])
        fun mm(id: String) = (plymeld.model.Resolver.resolveOrNull(setup, id, ctx)?.value as Number?)?.toDouble() ?: error("curved islands need ${id.replace("hardware.tool.", "hardware.tool.<key>.")}")
        val lim = curvedLimits(plan, setup, 0)
        val p = plymeld.core.decide.CurvedRegions.Params(mm("hardware.tool.head.nozzle.angle"), mm("hardware.tool.head.nozzle.height"),
            plan.layout.techniques[0].layerHeightMm ?: mm("plan.technique.flat.layer.height"), lim.minLayerMm)
        val parts = plan.parts
        val islands = plymeld.core.decide.CurvedIsland.resolve(parts, locked.map { plymeld.core.decide.CurvedIsland.Lock(it.key, it.seedXMm, it.seedYMm, it.startMm, it.layerMm) }, p, p.layerMm, lim.maxLayerMm)
            .map { it.placed(parts.minOf { s -> s.bounds()[0] }, parts.minOf { s -> s.bounds()[1] }) }
        return plymeld.core.check.CurvedCheck.readIslands(text, parts, zBase, islands, lim)
    }
    val spec = curvedSpec(plan, setup, 0) ?: return null
    return plymeld.core.check.CurvedCheck.read(text, plan.parts, zBase, spec)
}

/**
 * The curved band of placed object [j] for the check: its start above the bed (a top-following band's own start, or the
 * first layer's top if higher; the bed for layers that follow the underside), the tool's limits and the surface its layers
 * follow; null when the object has no curved band.
 */
internal fun curvedSpec(plan: plymeld.core.decide.Plan, setup: plymeld.model.Setup, j: Int): plymeld.core.check.CurvedCheck.Spec? {
    val curved = plymeld.core.decide.PrintTechnique.CURVED_LAYERS
    val under = plymeld.core.decide.PrintTechnique.CURVED_UNDERSIDE
    val lift = plymeld.core.decide.PrintTechnique.LIFTED_BRIDGE
    val band = plan.layout.techniques[j].bands.lastOrNull { it.technique == curved || it.technique == under || it.technique == lift } ?: return null
    val ctx = plymeld.model.Context(plan.layout.objectIndex[j])
    val s = plymeld.model.SliceSettings.of(setup, ctx)
    val lim = curvedLimits(plan, setup, j)
    // with Z anti-aliasing a layer holds a support's top from the tool's thinnest layer above its bottom (LiftWarp.of)
    if (band.technique == lift) return plymeld.core.check.CurvedCheck.Spec(0.0, lim, plymeld.core.check.CurvedCheck.Follows.LIFT,
        s.firstLayerHeightUm / 1000.0, s.layerHeightUm / 1000.0, if (s.zAntialias) lim.minLayerMm else null)
    return if (band.technique == under) plymeld.core.check.CurvedCheck.Spec(0.0, lim, plymeld.core.check.CurvedCheck.Follows.UNDERSIDE)
        else plymeld.core.check.CurvedCheck.Spec(maxOf(band.lo, s.firstLayerHeightUm / 1000.0), lim)
}

/** The tool's limits for placed object [j]'s curved layers (a band's or an island's): its thinnest and thickest layer, the slicing resolution, and the strictest head. */
internal fun curvedLimits(plan: plymeld.core.decide.Plan, setup: plymeld.model.Setup, j: Int): plymeld.core.check.CurvedCheck.Limits {
    val ctx = plymeld.model.Context(plan.layout.objectIndex[j])
    fun mm(id: String) = (plymeld.model.Resolver.resolveOrNull(setup, id, ctx)?.value as Number?)?.toDouble()
    val s = plymeld.model.SliceSettings.of(setup, ctx)
    // the strictest head of every tool printing the object (the object's first part's tool alone): the narrowest
    // cone, the lowest nozzle, the furthest reach each way; a tool with no head shape leaves the tip out
    val tips = plymeld.core.decide.Plan.toolsOf(setup, ctx.obj).values.map { part ->
        val pc = plymeld.model.Context(ctx.obj, part)
        fun pm(id: String) = (plymeld.model.Resolver.resolveOrNull(setup, id, pc)?.value as Number?)?.toDouble()
        Triple(pm("hardware.tool.head.nozzle.angle"), pm("hardware.tool.head.nozzle.height"), plymeld.core.decide.Plan.headReach(setup, ctx.obj, part)?.first)
    }
    val cone = if (tips.any { it.first == null }) null else tips.minOf { it.first!! }
    val protrusion = if (tips.any { it.second == null }) null else tips.minOf { it.second!! }
    val reach = if (tips.any { it.third == null }) null else tips.map { it.third!! }.let { rs ->
        plymeld.core.decide.Arrange.Reach(rs.maxOf { it.minusX }, rs.maxOf { it.plusX }, rs.maxOf { it.minusY }, rs.maxOf { it.plusY }) }
    return plymeld.core.check.CurvedCheck.Limits(mm("hardware.tool.layer.height.min") ?: error("curved layers need hardware.tool.<key>.layer.height.min"),
        mm("hardware.tool.layer.height.max") ?: error("curved layers need hardware.tool.<key>.layer.height.max"), s.resolutionUm / 1000.0,
        if (cone != null && protrusion != null) plymeld.core.check.CurvedCheck.Tip(cone, protrusion, reach) else null)
}

/** `plymeld plan -cf ... -m ... [-o plan.toml]`: each decision and why; with -o, the decisions as a config file to slice with. */
class PlanCommand : ModelConfigCommand("plan") {
    private val output by option("-o", "--output", metavar = "FILE", help = "write the decisions as a config file (TOML), for `plymeld slice -cf`").path()
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Show how plymeld would print the models (-m) with config files (-cf) and values (-cv), and why; -o writes the decisions as a config file."

    override fun run() {
        val pr = product(rePlan = true)
        val plan = pr.decide()
        echo(plan.describe().trimEnd())
        pr.firstLayerLines().forEach { (step, why) -> echo("[$step] $why") }
        explainOut(plan)
        output?.let { out ->
            out.parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(out, pr.planFile(plan))
            echo("plan written to $out: pass it with -cf to `plymeld slice`")
        }
    }
}

/**
 * `plymeld config [PATH] -cf ... -cv ...`: every value the config sets (plymeld's bundled files
 * included), where it was set, what it overrode, values taken over through `like`, and what each
 * formula works out to for the first tool and filament of the printing machine's loadout (--machine, job.machine or the only machine) (and, under the plan's first-layer speed and height,
 * for each loaded filament, the first-layer window's decision or the filament's or the machine's speed that applies there
 * instead of plymeld's bundled stand-in; under each filament's melt rate, its own or one for a machine or a tool, which of
 * them caps that filament in each tool of the machine that holds it, and what it works out to there).
 */
class ShowConfigCommand : ConfigCommand("config") {
    private val prefix by argument(help = "only paths starting with this").optional()
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Show the config the files (-cf) and values (-cv) add up to, after plymeld's bundled files: each value, where it was set, the values it overrode, and what each formula works out to."

    override fun run() {
        val s = stack()
        val c = try { plymeld.config.Config(s, emptyList(), machineOpt) } catch (e: plymeld.config.ConfigError) {
            throw UsageError("the config has ${e.problems.size} problem(s):\n" + e.problems.joinToString("\n") { "  $it" })
        }
        // the machine that prints: named, or the only one (several and none named: refused, never guessed)
        val machine = c.machine ?: throw UsageError(c.machineProblem ?: "which machine?")
        c.warnings.forEach { echo("warning: $it") }
        val shown = c.values.values.filter { prefix == null || it.key == prefix || it.key.startsWith("$prefix.") }
        if (shown.isEmpty()) { echo(if (prefix == null) "(no values set)" else "(nothing set under '$prefix')"); return }
        // formulas are worked out where they are used; shown here for the machine's loadout's first tool and filament
        echo("machine: $machine (${machineOpt?.let { "--machine" } ?: c.entry(listOf("job", "machine"))?.let { "job.machine, ${it.source}" } ?: "the only machine defined"})")
        val slots = plymeld.config.ConfigSetup.slots(c, machine, ArrayList())
        val site = plymeld.config.Site(machine = machine, plate = c.loadout("plate")?.value as String?, tool = slots.firstOrNull()?.tool, filament = slots.firstOrNull()?.filament)
        for (e in shown) {
            val worked = if (!plymeld.config.Formula.isFormula(e.value)) "" else {
                val dim = (plymeld.config.Schema.find(e.path)?.type as? plymeld.config.Schema.Type.Quantity)?.dim
                if (dim == null) "" else runCatching { c.quantity(e, dim, site) }.fold(
                    { v -> "  -> %s%s".format(java.util.Locale.ROOT, "%.6g".format(java.util.Locale.ROOT, v).let { if ('.' in it && 'e' !in it) it.trimEnd('0').trimEnd('.') else it }, if (dim.base.isEmpty()) "" else " ${dim.base}") },
                    { "  -> (worked out where used: ${it.message})" })
            }
            // kept (!): the value survives a re-plan; a plan file's other lines (a file carrying plan.source_fingerprint) are
            // locks for `plymeld slice` and decided again by `plymeld plan`, so for a plan they are not the value in force
            fun fileOf(source: String) = Regex("^(.*):\\d+$").find(source)?.groupValues?.get(1) ?: source
            val planFiles = s.all.filter { it.path.size >= 2 && it.path[0] == "plan" && it.path[1] == "source_fingerprint" }.map { fileOf(it.source) }.toSet()
            fun kept(x: plymeld.config.ConfigFiles.Entry) = when {
                x.keep -> "  kept (!)"
                x.path.isNotEmpty() && x.path[0] == "plan" && x.path.getOrNull(1) != "source_fingerprint" && fileOf(x.source) in planFiles -> "  a plan file's line: a lock for slice, decided again by plan"
                else -> ""
            }
            echo("${e.key} = ${show(e.value)}$worked  [${e.source}]${kept(e)}")
            for (o in s.overridden.filter { it.key == e.key }) echo("    overrides ${show(o.value)}  [${o.source}]${kept(o)}")
            // the plan's first-layer speed and height are only the stand-ins when plymeld's bundled ones: a first-layer window,
            // the filament's or the machine's wins, for every loaded filament (not only the first slot's: a tool changer's first
            // tool may hold none)
            val speedKey = e.key == "plan.mechanism.motion.first_layer.speed"; val heightKey = e.key == "plan.mechanism.adhesion.first_layer.height"
            if (speedKey || heightKey) for (f in slots.map { it.filament }.distinct()) {
                val fs = site.copy(tool = slots.first { it.filament == f }.tool, filament = f)
                val use = plymeld.config.ConfigSetup.windowAt(c, fs)
                val choice = use.choice; val w = use.window
                val locked = if (speedKey) plymeld.config.ConfigSetup.speedLock(c, fs) else plymeld.config.ConfigSetup.heightLock(c, fs)
                if (choice != null && w != null && locked == null) {
                    echo("    not used for '$f': first layer from the window: ${plymeld.config.FirstLayerWindow.mm(choice.heightMm)} at ${plymeld.config.FirstLayerWindow.mms(choice.speedMmS)}, " +
                        "${w.key} at ${fmtNum(use.tempC!!)} C, the first layer's nozzle temperature, ${choice.why}  [${w.source}]")
                    continue
                }
                if (choice != null && w != null) echo("    locked for '$f' over its first-layer window (${w.key}: ${plymeld.config.FirstLayerWindow.mm(choice.heightMm)} at ${plymeld.config.FirstLayerWindow.mms(choice.speedMmS)})")
                use.warning?.let { echo("    for '$f': $it") }
                if (speedKey) plymeld.config.ConfigSetup.firstLayerSpeed(c, fs)?.takeIf { it !== e }?.let { u ->
                    val v = runCatching { fmtNum(c.quantity(u, plymeld.config.Dimension.SPEED, fs)) + " mm/s" }.getOrElse { show(u.value) }
                    echo("    not used for '$f': its first layer prints at $v, ${u.key}  [${u.source}]")
                }
            }
            // the visible lines' speed and acceleration: plymeld's bundled stand-in gives way to the machine's motion plate reading
            // (ConfigSetup.withMachine), which the listing must say or it shows a value the slice does not use
            if ((e.key == "plan.mechanism.motion.visible.speed" || e.key == "plan.mechanism.motion.visible.acceleration") && e.source.startsWith(plymeld.config.ConfigFiles.BUNDLED_SOURCE)) {
                c.entry(listOf("hardware", "machine", machine, "motion") + e.path.takeLast(2))?.let { m ->
                    echo("    not used on machine '$machine': its own (its motion plate's reading) is ${show(m.value)}, ${m.key}  [${m.source}]")
                }
            }
            // a filament's melt rate (its own, or for a machine or a tool): which one caps it in each tool of this machine
            // that holds it (the tool's over the machine's over its own), worked out there
            if (e.path.size >= 5 && e.path[0] == "hardware" && e.path[1] == "filament" && e.path.takeLast(3) == listOf("melt", "flow", "max")) {
                val f = e.path[2]
                for (t in slots.filter { it.filament == f }.map { it.tool }.distinct()) {
                    val ts = site.copy(tool = t, filament = f)
                    val u = plymeld.config.ConfigSetup.meltEntry(c, ts) ?: continue
                    val v = runCatching { fmtNum(c.quantity(u, plymeld.config.Dimension.FLOW, ts)) + " mm3/s" }.getOrElse { show(u.value) }
                    echo(if (u === e) "    used for '$f' in tool '$t' on machine '$machine': $v"
                         else "    not used for '$f' in tool '$t' on machine '$machine': its melt cap there is $v, ${u.key}  [${u.source}]")
                }
            }
        }
    }
    private fun fmtNum(x: Double) = "%.6g".format(java.util.Locale.ROOT, x).let { if ('.' in it && 'e' !in it) it.trimEnd('0').trimEnd('.') else it }
    private fun show(v: Any): String = when (v) { is String -> "\"$v\""; is List<*> -> v.joinToString(", ", "[", "]") { show(it!!) }; else -> v.toString() }
}

/**
 * `plymeld schema [-o FILE] [--markdown FILE]`: the config schema as JSON Schema, for editors (TOML `#:schema` line, JSON
 * `$schema`); with --markdown, the settings catalog's tables written into that page below its marker line.
 */
class SchemaCommand : CliktCommand(name = "schema") {
    private val output by option("-o", "--output", metavar = "FILE", help = "file to write (default: print it)").path()
    private val markdown by option("--markdown", metavar = "PAGE", help = "rewrite the settings tables in this page below its marker line, instead of the JSON Schema").path()
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Print (or write, -o) the config schema as JSON Schema, for editors checking config files; or rewrite the settings catalog's tables (--markdown)."
    override fun run() {
        markdown?.let {
            java.nio.file.Files.writeString(it, plymeld.config.SchemaMarkdown.into(java.nio.file.Files.readString(it)))
            echo("settings tables written into $it"); return
        }
        val text = plymeld.config.SchemaJson.text()
        output?.let { java.nio.file.Files.writeString(it, text); echo("schema written to $it") } ?: echo(text, trailingNewline = false)
    }
}

/** `plymeld investigate ...`: commands that read the settings in a project or g-code file, for comparing with that file only. */
class InvestigateCommand : CliktCommand(name = "investigate") {
    init { subcommands(InvestigateSliceCommand(), InvestigatePlanCommand(), InvestigateCheckCommand(), InvestigateConfigCommand(), InvestigatePredictCommand()) }
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "INVESTIGATION ONLY: slice, plan, check, and config with the settings in a project or g-code file (--settings), and predict the time and filament of any g-code file, to compare plymeld's result with that file. Never how a print is set up."
    override fun run() = Unit

    companion object {
        /** The investigate commands' --settings help: the files whose settings they read. */
        const val SETTINGS_HELP = "a project .3mf, a .gcode file that carries its settings (; CONFIG_BLOCK_START ... _END), or machine.json,process.json"
    }
}
