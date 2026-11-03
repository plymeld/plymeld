package plymeld.commands

import java.nio.file.Files
import java.nio.file.Path

/**
 * The first-use setup, one implementation for both faces (`plymeld init` in the terminal, the window's setup): the
 * printers, each a machine of a bundled model with its tools, build plate, and network address; the filaments, each like
 * one of the bundled ones; and which filament is loaded in which tool. [printerAsks], [toolAsks] and [connectionAsks]
 * list what to ask, each with the default offered and where that default comes from; [files] renders the answers as the
 * config folder's files; [check] slices a test cube with them, as `plymeld slice` would, so a setup that comes out of it
 * plans, slices, and prints.
 */
object FirstUse {
    /** A bundled printer model, as the setup offers it; [arcs] and [excludeObject]: whether its command list has G2 / G3 and EXCLUDE_OBJECT_DEFINE. */
    class Model(val id: String, val name: String, val toolheads: Int, val dialect: String, val limits: Map<String, Double>,
                val arcs: Boolean, val excludeObject: Boolean, val accepts: Set<String>,
                /** What the model gives a machine to start from (hardware.model.<key>.build.*, gcode.*), with its evidence. */
                val build: Map<String, String> = emptyMap(), val buildWhy: String? = null,
                val start: String? = null, val end: String? = null, val gcodeWhy: String? = null)

    /** A bundled filament the setup offers to start from (its key, its kind: PLA, PETG, ...). */
    class Base(val key: String, val kind: String)

    /**
     * What the bundled config offers: printer models, build plates, filaments; every key it already uses; and the filament
     * kinds each plate has a bed temperature for (a kind with none cannot print on it).
     */
    class Choices(val models: List<Model>, val plates: List<String>, val filaments: List<Base>, val taken: Set<String>,
                  val plateKinds: Map<String, Set<String>>) {
        fun suits(plate: String, kind: String) = kind in plateKinds[plate].orEmpty()
    }

    /**
     * One value to ask for: the config path it sets under its entry, the question, the default offered (null: none, the
     * answer is required unless [optional]) and where that default comes from; [choices] for a value of a fixed set,
     * [secret] for one that goes in a secrets file, not the config.
     */
    class Ask(val path: String, val question: String, val default: String?, val why: String,
              val choices: List<String> = emptyList(), val optional: Boolean = false, val secret: Boolean = false)

    class Tool(val key: String, val index: Int, val values: Map<String, String>, val filament: String?)
    class Printer(val key: String, val model: String, val values: Map<String, String>, val tools: List<Tool>, val plate: String,
                  val secrets: Map<String, String>)
    class Filament(val key: String, val like: String, val color: String?)
    class Answers(val printers: List<Printer>, val filaments: List<Filament>)

    fun choices(): Choices {
        val c = plymeld.config.Config(plymeld.config.ConfigStack(plymeld.config.ConfigFiles.bundled()), emptyList())
        val models = plymeld.config.Models.library(c).all.map { d ->
            fun text(vararg rest: String) = c.entry(listOf("hardware", "model", d.id) + rest)?.value as? String
            Model(d.id, d.name, maxOf(1, d.toolheads.size), d.dialect, d.motion.mapValues { it.value.value },
                d.supports("G2") && d.supports("G3"), d.supports("EXCLUDE_OBJECT_DEFINE"),
                listOf("M140", "G28", "M190", "M109", "M104").filter(d::supports).toSet(),
                listOf("x", "y", "z").mapNotNull { a -> text("build", a)?.let { a to it } }.toMap(), text("evidence", "build"),
                text("gcode", "start"), text("gcode", "end"), text("evidence", "gcode"))
        }
        val filaments = c.keysOf("filament").map { k -> Base(k, (c.entry(listOf("hardware", "filament", k, "kind"))?.value as? String) ?: "") }
        val plates = c.keysOf("plate")
        val kinds = plates.associateWith { p ->
            c.values.values.filter { it.path.size == 7 && it.path.subList(0, 4) == listOf("hardware", "plate", p, "bed") && it.path.subList(5, 7) == listOf("temperature", "print") }
                .map { it.path[4] }.toSet()
        }
        return Choices(models, plates, filaments, c.keys.keys.toSet(), kinds)
    }

    /** The protocol `plymeld printer` speaks to a model's machines, by its g-code dialect; null: not one plymeld knows. */
    fun api(m: Model): String? = when (m.dialect) { "flashforge" -> "flashforge-http"; "qidi", "klipper" -> "moonraker"; else -> null }

    /** What to ask for a printer of model [m] (paths under hardware.machine.<key>). */
    fun printerAsks(m: Model): List<Ask> {
        fun limit(name: String, unit: String) = m.limits[name]?.let { "${num(it)} $unit" }
        val buildWhy = if (m.build.isEmpty()) "the printable area's size: from your printer's specifications"
            else "the model's build area (${m.buildWhy ?: m.name}); check yours"
        return listOf(
            Ask("build.x", "the build area's width (x)", m.build["x"], buildWhy),
            Ask("build.y", "the build area's depth (y)", m.build["y"], if (m.build.isEmpty()) buildWhy else "the model's, as above"),
            Ask("build.z", "the build height (z)", m.build["z"], if (m.build.isEmpty()) buildWhy else "the model's, as above"),
            Ask("build.origin", "where x 0, y 0 is", "front-left", "most printers: the bed's front-left corner", listOf("front-left", "center")),
            Ask("motion.acceleration.max", "its highest acceleration", limit("max_accel", "mm/s2"), "the model's firmware limit (${m.name})", optional = true),
            Ask("motion.print.speed", "the fastest it prints", limit("max_velocity", "mm/s"), "the model's firmware limit (${m.name}); a slower print is a lower number", optional = true),
            Ask("motion.print.acceleration", "the acceleration it prints at", "= machine.motion.acceleration.max / 2", "half its highest: plymeld's stand-in until a motion plate measures it"),
            Ask("motion.travel.speed", "the speed it travels at (moves that do not print)", "= machine.motion.print.speed", "as fast as it prints"),
            Ask("motion.travel.acceleration", "the acceleration it travels at", "= machine.motion.acceleration.max", "its highest"),
            Ask("firmware.arcs", "whether its firmware takes arcs (G2 / G3)", m.arcs.toString(),
                if (m.arcs) "the model's command list has G2 and G3" else "the model's command list has no G2 / G3", listOf("true", "false")),
            Ask("firmware.exclude_object", "whether its firmware can cancel one object mid-print", m.excludeObject.toString(),
                if (m.excludeObject) "the model's command list has EXCLUDE_OBJECT_DEFINE" else "the model's command list has no EXCLUDE_OBJECT_DEFINE", listOf("true", "false")),
            Ask("gcode.start", "the g-code before every print (one line; \\n between commands)", m.start?.replace("\n", "\\n") ?: start(m),
                if (m.start != null) "the model's firmware sequence (${m.gcodeWhy?.lines()?.first() ?: m.name})"
                else "plymeld's generic start, from the commands the model is known to accept: ${startSteps(m)}; your printer may have its own sequence"),
            Ask("gcode.end", "the g-code after every print (one line; \\n between commands)", m.end?.replace("\n", "\\n") ?: end(m),
                if (m.end != null) "the model's firmware sequence" else "plymeld's generic end: heaters off"))
    }

    /** What to ask for one tool (paths under hardware.tool.<key>). */
    fun toolAsks(): List<Ask> = listOf(
        Ask("bore", "the nozzle's bore (its opening)", "0.4 mm", "the most common nozzle; check the one fitted"),
        Ask("layer.height.min", "the thinnest layer it lays", "= tool.bore / 5", "a fifth of the bore: plymeld's stand-in"),
        Ask("layer.height.max", "the thickest layer it lays", "= tool.bore * 0.75", "three quarters of the bore: plymeld's stand-in"))

    /** How `plymeld printer` reaches a printer of model [m] (paths under hardware.machine.<key>.connection; secrets by name). */
    fun connectionAsks(m: Model): List<Ask> = listOf(Ask("host", "its address on your network (a host name or an IP address)", null,
        "shown on the printer's screen or your router's list; empty: no connection, plymeld writes files you copy over", optional = true)) +
        when (api(m)) {
            "flashforge-http" -> listOf(
                Ask("serial_number", "its serial number", null, "on the printer's screen (About)", secret = true),
                Ask("check_code", "its check code", null, "on the printer's screen, with the serial number", secret = true))
            "moonraker" -> listOf(Ask("api_key", "Moonraker's API key", null, "only if you set one; empty: none", optional = true, secret = true))
            else -> emptyList()
        }

    /** A key the user may give an entry: lower-case letters, digits, and '-', not one the bundled config or the answers use. */
    fun keyProblem(key: String, taken: Collection<String>): String? = when {
        !Regex("[a-z0-9][a-z0-9-]*").matches(key) -> "use lower-case letters, digits, and '-' (e.g. my-q2)"
        key in taken -> "'$key' is already a key (each key names one thing)"
        else -> null
    }

    /** The config folder's files for [a], by file name; secrets files under `secrets/` (never read as config). */
    fun files(a: Answers, dir: Path): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val printers = StringBuilder(HEADER + "# Your printers and their tools (written by plymeld init; edit freely).\n")
        for (p in a.printers) {
            // the firmware family: Klipper, the only one plymeld writes for
            printers.append("\n[hardware.machine.${p.key}]\nmodel = ${q(p.model)}\nfirmware.flavor = \"klipper\"\n")
            for ((k, v) in p.values) if (!k.startsWith("connection.")) printers.append("$k = ${q(v)}\n")
            for ((k, v) in p.values) if (k.startsWith("connection.")) printers.append("$k = ${q(v)}\n")
            if (p.secrets.isNotEmpty()) {
                val file = dir.resolve("secrets").resolve("${p.key}.toml")
                for (name in p.secrets.keys) printers.append("connection.credential.$name.file = ${q(file.toString())}\n")
                out["secrets/${p.key}.toml"] = "# ${p.key}'s credentials (written by plymeld init): keep this file to yourself, never commit it\n" +
                    p.secrets.entries.joinToString("") { (k, v) -> "$k = ${q(v)}\n" }
            }
            for (t in p.tools) {
                printers.append("\n[hardware.tool.${t.key}]\nmachine = ${q(p.key)}\nindex = ${q(t.index.toString())}\n")
                for ((k, v) in t.values) printers.append("$k = ${q(v)}\n")
            }
        }
        out["10_printers.toml"] = printers.toString()
        out["20_filaments.toml"] = HEADER + "# Your filaments (written by plymeld init): each starts from a bundled one (like) and may set its own values.\n" +
            a.filaments.joinToString("") { f -> "\n[hardware.filament.${f.key}]\nlike = ${q(f.like)}\n" + (f.color?.let { "color = ${q(it)}\n" } ?: "") }
        out["30_loadout.toml"] = HEADER + "# What is on each printer now: its build plate and the filament in each tool. Changes with every spool swap.\n" +
            a.printers.joinToString("") { p ->
                "\n[hardware.machine.${p.key}.loadout]\nplate = ${q(p.plate)}\n" + p.tools.filter { it.filament != null }.joinToString("") { "tool.${it.key}.filament = ${q(it.filament!!)}\n" }
            }
        if (a.printers.size > 1)
            out["40_job.toml"] = HEADER + "# The printer a command uses unless --machine names another.\n\n[job]\nmachine = ${q(a.printers.first().key)}\n"
        return out
    }

    /** Writes [files] into [dir] (secrets files readable by their owner only, where the system allows); the paths written. */
    fun write(files: Map<String, String>, dir: Path): List<Path> = files.map { (name, text) ->
        val p = dir.resolve(name)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
        if (name.startsWith("secrets/")) runCatching {
            Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        }
        p
    }

    /**
     * Slices a 20 mm test cube with the config files in [dir] on each printer, as `plymeld slice` would: what each printed
     * line said, and whether it sliced. A setup that passes plans and slices; printing it is `plymeld printer send`.
     */
    fun check(dir: Path, printers: List<String>, say: (String) -> Unit): Boolean {
        // the files are checked in their folder as written; a credential's file is not read by a slice
        val work = Files.createTempDirectory("plymeld-init-")
        try {
            val cube = work.resolve("test-cube.stl")
            Files.writeString(cube, CUBE_STL)
            var ok = true
            val configs = Files.list(dir).use { s -> s.filter { it.toString().endsWith(".toml") }.map { it.toString() }.sorted().toList() }
            for (p in printers) {
                val args = configs.flatMap { listOf("-cf", it) } + listOf("--machine", p, "-m", cube.toString(), "-o", work.resolve("$p.gcode").toString())
                val status = Commands.invoke(listOf("slice") + args, { }, say)
                if (status == 0) say("$p: a 20 mm test cube sliced with this setup") else ok = false
            }
            return ok
        } finally {
            runCatching { Files.walk(work).sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    /** The printers in [a] given a network address: the ones [reach] can ask. */
    fun connected(a: Answers): List<String> = a.printers.filter { it.values["connection.host"] != null }.map { it.key }

    /**
     * Asks [printer] for its state with the config files in [dir], as `plymeld printer status` would: whether its address and
     * credentials reach it. What it said goes to [say]; true when it answered.
     */
    fun reach(dir: Path, printer: String, say: (String) -> Unit): Boolean {
        val configs = Files.list(dir).use { s -> s.filter { it.toString().endsWith(".toml") }.map { it.toString() }.sorted().toList() }
        return Commands.invoke(listOf("printer", "status") + configs.flatMap { listOf("-cf", it) } + listOf("--machine", printer), say, say) == 0
    }

    /**
     * [files] checked before anything is written: written to a folder of their own (credentials left out: a slice does not
     * read them) and a test cube sliced on each of [printers] ([check]); the folder removed after. True when every one sliced.
     */
    fun trial(files: Map<String, String>, printers: List<String>, say: (String) -> Unit): Boolean {
        val dir = Files.createTempDirectory("plymeld-setup-check-")
        return try {
            write(files.filterKeys { !it.startsWith("secrets/") }, dir)
            check(dir, printers, say)
        } finally { runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    /** Whether [dir] can take a first-use setup: null when it holds no .toml file, else why not. */
    fun folderProblem(dir: Path): String? {
        val existing = if (Files.isDirectory(dir)) Files.list(dir).use { s -> s.filter { it.toString().endsWith(".toml") }.toList() } else emptyList()
        return if (existing.isEmpty()) null else "$dir already holds config files (${existing.joinToString { it.fileName.toString() }}): the first-use " +
            "setup writes an empty folder only; change values with plymeld edit"
    }

    /**
     * plymeld's generic start for [m]: the bed heated, the head homed, the bed and the nozzle waited for, each step only with
     * a command the model is known to accept (a printer that homes by itself lists no G28); template variables for the
     * temperatures. Lines joined by a written \\n, as the setup asks for one line.
     */
    fun start(m: Model): String = listOfNotNull(
        "M140 S{first_layer_bed_temperature}".takeIf { "M140" in m.accepts }, "G28".takeIf { "G28" in m.accepts },
        "M190 S{first_layer_bed_temperature}".takeIf { "M190" in m.accepts },
        "M109 S{first_layer_nozzle_temperature}".takeIf { "M109" in m.accepts }).joinToString("\\n")

    /** What [start] does for [m], in words. */
    private fun startSteps(m: Model): String = listOfNotNull("heat the bed".takeIf { "M140" in m.accepts }, "home".takeIf { "G28" in m.accepts },
        "wait for the bed".takeIf { "M190" in m.accepts }, "wait for the nozzle".takeIf { "M109" in m.accepts }).joinToString(", ").ifEmpty { "nothing it accepts" } +
        (if ("G28" !in m.accepts) " (the model lists no G28: it homes by itself, or the start needs its own homing)" else "")

    /** plymeld's generic end for [m]: the heaters off, with the commands the model accepts. */
    fun end(m: Model): String = listOfNotNull("M104 S0".takeIf { "M104" in m.accepts }, "M140 S0".takeIf { "M140" in m.accepts }).joinToString("\\n")

    private const val HEADER = ""

    /** [v] as a TOML string: a multi-line value (its \n written as lines) as a literal block, else a basic string. */
    private fun q(v: String): String {
        val text = v.replace("\\n", "\n")
        return if ('\n' in text) "\"\"\"\n" + text.replace("\\", "\\\\").replace("\"\"\"", "\\\"\\\"\\\"") + "\n\"\"\""
        else "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun num(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    /** A 20 mm cube, ASCII STL, its corner at the origin (the slice centers it on the plate). */
    private val CUBE_STL: String = run {
        val s = 20.0
        val v = listOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(s, 0.0, 0.0), doubleArrayOf(s, s, 0.0), doubleArrayOf(0.0, s, 0.0),
            doubleArrayOf(0.0, 0.0, s), doubleArrayOf(s, 0.0, s), doubleArrayOf(s, s, s), doubleArrayOf(0.0, s, s))
        val f = listOf(intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7), intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
            intArrayOf(1, 2, 6), intArrayOf(1, 6, 5), intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7))
        buildString {
            append("solid cube\n")
            for (t in f) {
                append(" facet normal 0 0 0\n  outer loop\n")
                for (i in t) append("   vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}\n")
                append("  endloop\n endfacet\n")
            }
            append("endsolid cube\n")
        }
    }
}
