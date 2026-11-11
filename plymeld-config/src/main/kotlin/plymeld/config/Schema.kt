package plymeld.config

/**
 * The config schema: every path declared once,
 * with its type, meaning, and — for now — the setting of today's pipeline it feeds. `<key>` stands
 * for an entry's key. Paths under `job` and `plan` may also be scoped to an object
 * (`job.object.<key>.…`, `plan.object.<key>.…`) or a part (`plan.object.<key>.part.<key>.…`).
 *
 * Plan groups marked INHERITED hold what today's flat-layer pipeline needs to run, named by what they
 * do; they are placeholders, to be redone technique by technique.
 */
object Schema {
    sealed class Type(val label: String) {
        class Quantity(val dim: Dimension) : Type(when (dim) {
            Dimension.RATIO -> "a multiplier (0.15 or 15%)"; Dimension.COUNT -> "a count"; Dimension.WEIGHT -> "a weight"
            else -> "a ${dim.name.lowercase()} (${dim.base})" })
        object Bool : Type("true or false")
        object Text : Type("text")
        class Choice(val options: List<String>) : Type("one of ${options.joinToString()}")
        /** A reference to an entry of [kind] by its key; [none]: what an empty value ("") means where one is allowed (null: not allowed). */
        class Ref(val kind: String, val none: String? = null) : Type("the key of a $kind" + (none?.let { ", or \"\" for $it" } ?: ""))
        class Choices(val options: List<String>) : Type("a list of ${options.joinToString()}")
        /** A list of [size] plain numbers. */
        class Numbers(val size: Int) : Type("a list of $size numbers")
        /** x, y, and z in one value with one unit ("10 20 5 mm"): a box's corner. */
        class Point(val dim: Dimension) : Type("a point, x y z and a unit (\"10 20 5 ${dim.base}\")")
    }

    /**
     * One declared path; [feeds]: the pipeline setting it sets today (`plymeld.model.Catalog`), if any. That setting's id
     * is the path without its `<key>` segments ([idOf]), so a declaration says only whether it feeds one.
     */
    class Decl(val path: String, val type: Type, val meaning: String, fed: Boolean = false) {
        val segments: List<String> = path.split('.')
        val feeds: String? = if (fed) idOf(path) else null
        /** False for a declared value nothing reads yet (its meaning says so): setting one earns a warning. */
        val used: Boolean = "(not yet used" !in meaning
        /** Where the value may be set: a value under a scope the code never reads is refused, not ignored. */
        val scopes: Set<Scope> = scopesOf(segments)
    }

    /**
     * Where a value is set: for the whole plate, for one object (`plan.object.<key>.…`, `job.object.<key>.…`), for one part of
     * it, or for a region of it (`plan.object.<key>.region.<key>.…`: the lines within a box, a height range, or by the faces they lay).
     */
    enum class Scope(val words: String) { PLATE("the plate"), OBJECT("an object"), PART("a part"), REGION("a region") }

    /** The values the code reads per region (Regions): the motion caps. Any other value under a region is refused. */
    val REGION_VALUES = listOf("plan.mechanism.motion.speed.max", "plan.mechanism.motion.acceleration.max")

    /**
     * The scopes a declared path may be set at. `plan.*` values are read per object and
     * per part (the resolver's context) unless the code reads them once for the plate: the pauses, the object order,
     * the placement and the pose switch, the purge tower, the plan's fingerprints; a locked support spot and an insert
     * belong to an object, not a part; `job.*` values are read per object at most (the weights, the techniques allowed).
     * Everything else (`hardware.*`, `model.*`, `method.*`) is the plate's. A region's values are only [REGION_VALUES].
     */
    fun scopesOf(segments: List<String>): Set<Scope> {
        val p = segments.joinToString(".")
        fun under(vararg heads: String) = heads.any { p == it || p.startsWith("$it.") }
        return when {
            under("plan.pause", "plan.objects.printing", "plan.objects.order", "plan.bridge_order", "plan.placement", "plan.pose", "plan.source_fingerprint", "plan.mechanism.purge.tower") -> setOf(Scope.PLATE)
            under("plan.insert") -> setOf(Scope.PLATE, Scope.OBJECT)
            p in REGION_VALUES -> setOf(Scope.PLATE, Scope.OBJECT, Scope.PART, Scope.REGION)
            segments[0] == "plan" -> setOf(Scope.PLATE, Scope.OBJECT, Scope.PART)
            under("job.machine") -> setOf(Scope.PLATE)
            segments[0] == "job" -> setOf(Scope.PLATE, Scope.OBJECT)
            else -> setOf(Scope.PLATE)
        }
    }

    /**
     * The scope [path] is set at, by its shape: `job.object.K.x` / `plan.object.K.x` an object's, `plan.object.K.part.P.x` a part's,
     * `plan.object.K.region.R.x` a region's.
     */
    fun scopeOf(path: List<String>): Scope = when {
        path.size > 5 && (path[0] == "job" || path[0] == "plan") && path[1] == "object" && path[3] == "part" -> Scope.PART
        path.size > 5 && path[0] == "plan" && path[1] == "object" && path[3] == "region" -> Scope.REGION
        path.size > 3 && (path[0] == "job" || path[0] == "plan") && path[1] == "object" -> Scope.OBJECT
        else -> Scope.PLATE
    }

    /** Whether [path] matches a declaration as written (an object-level declaration such as `plan.object.<key>.pose.turn`), not through unscoping. */
    fun declaredAsIs(path: List<String>): Boolean = match(path) != null

    /**
     * The id of the setting [path] sets (ids are exposed, so they follow the config keys): the path without the
     * names you give, its `<key>` segments. `hardware.tool.<key>.layer.height.min` -> `hardware.tool.layer.height.min`.
     */
    fun idOf(path: String): String = path.split('.').filter { it != "<key>" }.joinToString(".")

    /** Kinds of keyed entries and where they live; their keys share one namespace (parts: unique within their object). */
    val ENTRY_TABLES = linkedMapOf(
        "machine" to "hardware.machine", "model" to "hardware.model", "plate" to "hardware.plate", "tool" to "hardware.tool",
        "filament" to "hardware.filament", "object" to "model.object", "pause" to "plan.pause")

    /** The goals `job.priorities.<goal>` weighs, in their order: plymeld.model.Goal's ids. */
    val GOALS = plymeld.model.Goal.entries.map { it.id }
    /**
     * The measures a weight may name below the goals (`job.priorities.measure.<measure>`), e.g. `measure.unsupported_area = 50`
     * to weigh the droop on its own: plymeld.model.Metric's ids (one list, read here and by the decisions).
     */
    val MEASURES = plymeld.model.Metric.entries.map { it.id }
    private val GOAL_MEANING = mapOf(
        "quality" to "how the finished print looks, judged by the print artifacts expected on it (layer lines and stair-steps, seams, support scars, drooped or curled overhangs, stringing and blobs, ringing, over- and under-filled surfaces, color bleed), each by how severe and how visible it is",
        "accuracy" to "how closely the print matches the model's dimensions: measured today by how far the model's flat horizontal faces are moved to the nearest layer top (height_error); hole sizes and fits, flatness, elephant's foot, and warping out of shape are not measured yet",
        "speed" to "total time from start to a finished print on the bed (heating, levelling, homing, tool and filament changes with their purges, temperature changes, pauses, as well as moving); not a failed print's time, nor your own work afterwards",
        "material" to "all filament used, by mass (object, support, brims and skirts, prime lines, towers, purges; by volume where a filament has no density); not a failed print's filament",
        "strength" to "the finished print's ability to withstand the loads it will see in use; with no loads marked, its weakest direction (usually across the layers)",
        "reliability" to "the chance that the print finishes usable, run after run, without a failure that would likely need a reprint (lost adhesion, warping that lifts the part, a part knocked loose or tipped over, a nozzle collision, spaghetti, a failed tool change, running out of filament); on a plate, one object failing counts")

    private val all = ArrayList<Decl>()
    val decls: List<Decl> get() = all
    private fun d(path: String, type: Type, meaning: String, feeds: Boolean = false) { all += Decl(path, type, meaning, feeds) }
    private fun q(path: String, dim: Dimension, meaning: String, feeds: Boolean = false) = d(path, Type.Quantity(dim), meaning, feeds)

    init {
        // ---- hardware: the machine
        val m = "hardware.machine.<key>"
        d("$m.like", Type.Ref("machine"), "start from another machine's values")
        // the shipped models: the bundled 03_models.toml (plymeld-config resources)
        d("$m.model", Type.Ref("model"), "the printer model it is, by the key of a model: one plymeld ships (generic-klipper, qidi-q2, flashforge-c5) or one you describe (hardware.model.<key>): its g-code commands, head shape, and default g-code dialect; several machines may be the same model", feeds = true)
        q("$m.build.x", Dimension.LENGTH, "printable width (X)")
        q("$m.build.y", Dimension.LENGTH, "printable depth (Y)")
        q("$m.build.z", Dimension.LENGTH, "printable height (Z)", feeds = true)
        d("$m.build.origin", Type.Choice(listOf("front-left", "center")), "where 0,0 is on the bed")
        q("$m.motion.print.speed", Dimension.SPEED, "fastest printing move (the maker's print speed; travel has motion.travel.speed): hidden lines (inner walls, infill, solid, support) print at it unless the filament's melt rate caps them first, and no printing move goes faster", feeds = true)
        q("$m.motion.acceleration.max", Dimension.ACCEL, "highest acceleration: what formulas read (the first-use setup writes the print and travel accelerations as formulas of it); nothing else reads it directly: time predictions take the print and travel accelerations from the config, the rest from the machine description")
        q("$m.motion.z.speed.max", Dimension.SPEED, "fastest Z move (Klipper's [printer] max_z_velocity), for time predictions; unset = its model's", feeds = true)
        q("$m.motion.z.acceleration.max", Dimension.ACCEL, "highest Z acceleration (Klipper's [printer] max_z_accel), for time predictions; unset = its model's", feeds = true)
        q("$m.motion.z.step", Dimension.LENGTH, "the smallest Z move the machine makes (its Z motor's step as configured: Klipper's rotation_distance / (full_steps_per_rotation x microsteps), with the Z axis's own gearing): flat layers' tops are held to its whole multiples above the object's bottom, so no layer alternates between two thicknesses where its height is not a multiple (bands); unset = layers as planned", feeds = true)
        q("$m.motion.corner.speed", Dimension.SPEED, "speed kept through a square corner (Klipper's [printer] square_corner_velocity), for time predictions; unset = its model's", feeds = true)
        q("$m.motion.travel.speed", Dimension.SPEED, "speed of moves that do not print", feeds = true)
        q("$m.motion.print.acceleration", Dimension.ACCEL, "acceleration of printing moves", feeds = true)
        q("$m.motion.travel.acceleration", Dimension.ACCEL, "acceleration of moves that do not print", feeds = true)
        q("$m.motion.first_layer.speed", Dimension.SPEED, "the machine's default speed of everything on the first layer, for a filament that sets none of its own (hardware.filament.<key>.motion.first_layer.speed); unset = plymeld's stand-in (plan.mechanism.motion.first_layer.speed in its bundled defaults)")
        // the visible lines' speed and acceleration as measured on the machine (its motion plate, `plymeld calibrate motion values`):
        // read by ConfigSetup.withMachine into plan.mechanism.motion.visible.*, under a value a config of the user's sets there
        q("$m.motion.visible.speed", Dimension.SPEED, "the speed this machine lays a visible line (outer walls, top and bottom surfaces) at without ringing, as its motion plate measured it (`plymeld calibrate motion values` writes it); used for plan.mechanism.motion.visible.speed unless a config of yours sets that; unset = plymeld's stand-in")
        q("$m.motion.visible.acceleration", Dimension.ACCEL, "the acceleration this machine lays a visible line at without ringing, as its motion plate measured it; used for plan.mechanism.motion.visible.acceleration unless a config of yours sets that; unset = plymeld's stand-in")
        d("$m.firmware.flavor", Type.Choice(listOf("klipper")), "the firmware family its g-code is written for", feeds = true)
        d("$m.firmware.dialect", Type.Choice(plymeld.machine.Dialect.entries.map { it.id }), "the comments and acceleration command its files are written with, as the maker's own files for its printers have them (flashforge: `;LAYER_CHANGE`, `;TYPE:`, SET_VELOCITY_LIMIT, and the file header the maker's own slicer writes; qidi: `; CHANGE_LAYER`, `; FEATURE:`, M204; klipper: the flashforge comments and SET_VELOCITY_LIMIT with no maker's header), so its screen and previewers read them; unset = its model's default (model)", feeds = true)
        d("$m.firmware.arcs", Type.Bool, "the firmware takes arcs (G2/G3)", feeds = true)
        q("$m.firmware.arc_segment", Dimension.LENGTH, "the length of the straight pieces the firmware cuts an arc into (Klipper's [gcode_arcs] resolution): an arc shorter than one piece would be run as a single chord across it, so such a stretch is written as the path's own lines; unset = not known, every arc that fits is written", feeds = true)
        d("$m.firmware.exclude_object", Type.Bool, "the firmware can cancel one object mid-print", feeds = true)
        d("$m.tool_change.kind", Type.Choice(listOf("none", "swap_filament", "swap_tool")), "how it changes filament (not yet used)")
        q("$m.tool_change.time", Dimension.TIME, "the machine's own time for one change, beyond the g-code's moves", feeds = true)
        q("$m.pressure_advance", Dimension.TIME, "pressure advance every tool prints with (Klipper's, in seconds, e.g. \"0.035 s\") unless the tool or the filament sets its own; none set = the firmware's own, and plymeld writes none", feeds = true)
        d("$m.gcode.start", Type.Text, "g-code before the print (plymeld's template variables: nozzle_temperature, first_layer_nozzle_temperature, bed_temperature, first_layer_bed_temperature, layers, max_z, max_layer_z, initial_tool (the tool the print starts with, as T<n>), max_filament_flow (mm3/s), filament_diameter (mm), z_hop (mm, the travel lift: the first filament's retract.z_hop), max_print_height (mm, the machine's build.z))", feeds = true)
        d("$m.gcode.end", Type.Text, "g-code after the print (the same variables as the start)", feeds = true)
        d("$m.gcode.pause", Type.Text, "g-code that pauses for the user", feeds = true)
        d("$m.gcode.level", Type.Text, "g-code that levels the bed (e.g. a bed mesh), run after the start template when a print asks for levelling (a calibration plate does unless --no-level); empty = none known for the machine", feeds = true)
        d("$m.gcode.layer_change", Type.Text, "g-code at each layer change (layer_z, layer_number)", feeds = true)
        d("$m.gcode.pictures", Type.Text, "the preview pictures its screen reads from the g-code file, as pixel sizes WIDTHxHEIGHT separated by commas (e.g. \"96x96, 300x300\"): plymeld draws the plate's parts into each and writes it as a PNG in a `; thumbnail begin` comment block near the top of the file; unset or empty = no picture", feeds = true)
        // how it is reached: the protocol ids are plymeld-printer's Protocol
        // (a test there holds the two lists equal); a credential is the NAME of an environment variable, never a value
        d("$m.connection.api", Type.Choice(listOf("flashforge-http", "moonraker")), "the protocol `plymeld printer` speaks to it: flashforge-http = Flashforge's LAN HTTP API (the Creator 5), moonraker = Moonraker's HTTP API on a Klipper machine")
        d("$m.connection.host", Type.Text, "its address on the network (a host name or an IP address)")
        d("$m.connection.start.level", Type.Bool, "`plymeld printer send --print` asks the printer to level the bed before it prints (flashforge-http: levelingBeforePrint); --level / --no-level win over it; unset = false. A printer whose API has no such request (moonraker: levelling there is g-code, gcode.start or gcode.level) refuses it")
        q("$m.connection.port", Dimension.COUNT, "the API's port; unset = the API's own (8898 for flashforge-http, 7125 for moonraker)")
        q("$m.connection.timeout", Dimension.TIME, "how long to wait for the printer's answer; unset = 8 s")
        d("$m.connection.credential.<key>.env", Type.Text, "the NAME of the environment variable holding one credential the protocol needs (flashforge-http: serial_number and check_code; moonraker: api_key, and for its shell ssh_password or ssh_key_file (the path of a private key), all optional); the value itself never goes in a config file")
        d("$m.connection.credential.<key>.file", Type.Text, "a local secrets file (a path, ~/ allowed) holding the credential as a `<name> = \"value\"` line, a second source beside .env: when both are set and the environment variable has a value, the environment wins; never commit the file")
        d("$m.connection.camera.url", Type.Text, "its camera's URL (a snapshot URL, or an MJPEG stream, as its own web server gives it); unset = the API's own where it names one, else no camera")
        // the printer's shell, for what its API does not report (moonraker: the QIDI Box's slots from Klipper's save_variables file)
        d("$m.connection.ssh.user", Type.Text, "the user name for a shell on the printer over SSH (moonraker: `printer loadout` reads the QIDI Box's slots from Klipper's save_variables file through it); unset = no shell, and no loadout from a Moonraker machine")
        d("$m.connection.ssh.host", Type.Text, "the address the shell is reached at; unset = connection.host")
        q("$m.connection.ssh.port", Dimension.COUNT, "the SSH port; unset = 22")
        d("$m.connection.ssh.variables_file", Type.Text, "the path on the printer of Klipper's save_variables file, where the QIDI Box records its slots; unset = /home/mks/printer_data/config/saved_variables.cfg (a QIDI Q2's layout, as seen on one)")
        d("$m.connection.ssh.accept_new", Type.Bool, "trust the printer's SSH host key on first use and record it in ~/.ssh/known_hosts (a risk: a machine in between can pose as the printer that first time; see SECURITY.md); unset or false = an unknown or changed host key is refused")
        // what is on it now
        d("$m.loadout.plate", Type.Ref("plate"), "the build plate on its bed now (a calibration plate takes it unless --plate names another)")
        d("$m.loadout.tool.<key>.filament", Type.Ref("filament", none = "none loaded"), "the filament in one of its tools (a model's part numbers are the tools' indexes); \"\" = the tool is empty, which lets a later config file unload a tool an earlier one loads")
        d("$m.loadout.slot.<key>.filament", Type.Ref("filament", none = "none loaded"), "for one nozzle fed by several spools: the filament in a slot (a model's part numbers are the slots); \"\" = the slot is empty, which lets a later config file unload a slot an earlier one loads")
        d("$m.loadout.slot.<key>.tool", Type.Ref("tool"), "the tool a slot feeds (default: the machine's only tool)")
        // ---- hardware: printer models (what a model accepts and is, with evidence); the shipped ones in the
        // bundled 03_models.toml, a user's own beside them
        val md = "hardware.model.<key>"
        d("$md.like", Type.Ref("model"), "start from another model's values (a shipped model, or one of yours), every command it accepts included")
        d("$md.name", Type.Text, "the model's name, as the window and messages show it")
        d("$md.profile_name", Type.Text, "the printer_model another slicer's profile gives this model: the profile importer maps it to this model")
        d("$md.dialect", Type.Choice(plymeld.machine.Dialect.entries.map { it.id }), "the g-code dialect a machine of this model writes unless it sets its own (hardware.machine.<key>.firmware.dialect)")
        d("$md.notes", Type.Text, "notes on the model's description: where its facts come from, what is not known")
        d("$md.command.<key>", Type.Text, "a g-code command the model accepts (the key is the command word, e.g. PRINT_START): the writer refuses every command its model does not list. The value is its evidence, one \"kind: detail\" per line (kinds: tested, measured, firmware_config, used_by_firmware, documented, manufacturer_profile, observed, stand_in; a line without one counts as your config's; a \"purpose: ...\" line says what it does); empty is allowed")
        q("$md.motion.speed.max", Dimension.SPEED, "the firmware's top speed (Klipper's [printer] max_velocity), for time predictions")
        q("$md.motion.acceleration.max", Dimension.ACCEL, "the firmware's top acceleration (Klipper's [printer] max_accel), for time predictions")
        q("$md.motion.z.speed.max", Dimension.SPEED, "the firmware's top Z speed (Klipper's [printer] max_z_velocity), for time predictions")
        q("$md.motion.z.acceleration.max", Dimension.ACCEL, "the firmware's top Z acceleration (Klipper's [printer] max_z_accel), for time predictions")
        q("$md.motion.corner.speed", Dimension.SPEED, "the speed the firmware keeps through a square corner (Klipper's [printer] square_corner_velocity), for time predictions")
        for (limit in listOf("speed.max", "acceleration.max", "z.speed.max", "z.acceleration.max", "corner.speed"))
            d("$md.evidence.motion.$limit", Type.Text, "the evidence of motion.$limit, \"kind: detail\"")
        q("$md.motion.print.speed", Dimension.SPEED, "the maker's top print speed, which may be below motion.speed.max (the axes' top speed, travel's): the first-use setup's default for hardware.machine.<key>.motion.print.speed; unset = motion.speed.max")
        d("$md.evidence.motion.print.speed", Type.Text, "the evidence of motion.print.speed, \"kind: detail\"")
        // what a machine of this model starts from in the first-use setup (plymeld init offers them as its defaults)
        for (a in listOf("x", "y", "z"))
            q("$md.build.$a", Dimension.LENGTH, "the build area's ${if (a == "z") "height" else "size along $a"} on a machine of this model, as its maker gives it: the first-use setup's default for hardware.machine.<key>.build.$a")
        d("$md.evidence.build", Type.Text, "the evidence of build.x / .y / .z, \"kind: detail\"")
        d("$md.gcode.start", Type.Text, "the g-code before a print as the model's firmware defines it (e.g. its start macro; template variables as in hardware.machine.<key>.gcode.start): the first-use setup's default; unset = plymeld's generic start from the commands the model accepts")
        d("$md.gcode.end", Type.Text, "the g-code after a print as the model's firmware defines it: the first-use setup's default; unset = plymeld's generic end")
        d("$md.gcode.pause", Type.Text, "the g-code that pauses a print on this model: the first-use setup's default for hardware.machine.<key>.gcode.pause; unset = none offered")
        d("$md.gcode.pictures", Type.Text, "the preview pictures this model's screen reads, as in hardware.machine.<key>.gcode.pictures: the first-use setup's default; unset = none offered")
        d("$md.evidence.gcode", Type.Text, "the evidence of gcode.start / .end / .pause / .pictures, \"kind: detail\" (one line each)")
        d("$md.fan.<key>.role", Type.Choice(listOf("part_cooling", "auxiliary_part_cooling", "chamber_circulation", "automatic", "unassigned")), "what a fan (by its name) is for (not yet used)")
        d("$md.fan.<key>.command", Type.Text, "the command that drives a fan, {s255} standing for its speed 0-255 (not yet used)")
        d("$md.fan.<key>.evidence", Type.Text, "where a fan's facts come from (not yet used)")
        d("$md.heater.<key>.section", Type.Text, "a heater's section in the firmware's config (not yet used)")
        q("$md.heater.<key>.temperature.max", Dimension.TEMPERATURE, "a heater's highest temperature, as its firmware sets it (not yet used)")
        d("$md.heater.<key>.evidence", Type.Text, "where a heater's facts come from (not yet used)")
        q("$md.toolhead.<key>.head.nozzle.angle", Dimension.ANGLE, "a toolhead's (by its index, from 0) nozzle tip cone, its side's angle from horizontal; a tool's head.nozzle.angle wins over it")
        q("$md.toolhead.<key>.head.nozzle.height", Dimension.LENGTH, "how far a toolhead's nozzle tip sits below the rest of the head; a tool's head.nozzle.height wins over it")
        for (axis in listOf("x", "y")) {
            q("$md.toolhead.<key>.head.size.$axis", Dimension.LENGTH, "a toolhead's size along the machine's ${axis.uppercase()} axis, seen from below (its underside as a rectangle), with head.nozzle.$axis; a tool's head.size wins over it")
            q("$md.toolhead.<key>.head.nozzle.$axis", Dimension.LENGTH, "a toolhead's nozzle position within its underside along ${axis.uppercase()}: its distance from the head's edge on the machine's -${axis.uppercase()} side, with head.size.$axis; a tool's head.nozzle.$axis wins over it")
        }
        q("$md.toolhead.<key>.temperature.max", Dimension.TEMPERATURE, "the hottest the maker lets a toolhead's nozzle be set: the first-use setup's default for its tool's temperature.max")
        q("$md.toolhead.<key>.head.radius", Dimension.LENGTH, "a toolhead as a circle around its nozzle tip, its radius (one object at a time keeps objects this far apart when no head outline is known)")
        q("$md.toolhead.<key>.gantry.height", Dimension.LENGTH, "how far the gantry's rod sits above a toolhead's nozzle tip (one object at a time keeps every object but the last below it)")
        q("$md.toolhead.<key>.gantry.distance", Dimension.LENGTH, "how far the gantry's rod sits from a toolhead's nozzle tip, across")
        q("$md.toolhead.<key>.lid.height", Dimension.LENGTH, "how far the machine's lid sits above a toolhead's nozzle tip")
        q("$md.toolhead.<key>.heat.time", Dimension.TIME, "how long a toolhead's nozzle takes to heat to print temperature: on a multi-nozzle machine, the next nozzle heated this long (print time) before its change; a tool's heat.time wins over it")
        d("$md.toolhead.<key>.evidence", Type.Text, "where a toolhead's geometry comes from, \"kind: detail\" per line")
        // ---- hardware: plates
        val p = "hardware.plate.<key>"
        d("$p.like", Type.Ref("plate"), "start from another plate's values")
        d("$p.surface", Type.Text, "the build surface (smooth, textured, glass, ...) (not yet used)")
        q("$p.bed.<key>.temperature.print", Dimension.TEMPERATURE, "bed temperature for a filament kind (PLA, PETG, ...) on this plate, after the first layer (a filament's own for the plate wins: hardware.filament.<key>.bed.<plate>)")
        q("$p.bed.<key>.temperature.first_layer", Dimension.TEMPERATURE, "bed temperature on the first layer for a filament kind; unset = as the rest (temperature.print)")
        d("$p.suits.<key>", Type.Bool, "whether the surface suits a filament kind (PLA, PETG, ...): false = plymeld warns when a print puts that kind on it (it may not stick, or may bond hard enough to damage the surface); unset = no opinion")
        q("$p.first_layer.squish", Dimension.LENGTH, "how far the bed spreads the first layer outward", feeds = true)
        d("$p.bottom_finish", Type.Text, "what the bottom face looks like (not yet used)")
        // ---- hardware: tools
        val t = "hardware.tool.<key>"
        d("$t.like", Type.Ref("tool"), "start from another tool's values")
        d("$t.machine", Type.Ref("machine"), "the machine it is on")
        q("$t.index", Dimension.COUNT, "its number on the machine (1 = the first)")
        q("$t.bore", Dimension.LENGTH, "nozzle bore", feeds = true)
        q("$t.line.width.min", Dimension.LENGTH, "narrowest line it lays (variable-width walls' thresholds follow from it)")
        q("$t.line.width.max", Dimension.LENGTH, "widest line it lays (not yet used)")
        q("$t.layer.height.min", Dimension.LENGTH, "thinnest layer it lays", feeds = true)
        q("$t.layer.height.max", Dimension.LENGTH, "thickest layer it lays", feeds = true)
        q("$t.first_layer.height.max", Dimension.LENGTH, "thickest FIRST layer it lays (the first layer only; the layers above keep layer.height.max): the first-layer window's heights are taken up to it, and a first-layer height set above it earns a warning; unset = layer.height.max")
        // the loaded head's shape around the nozzle: the head's XY size, the nozzle's XY position within it, the
        // nozzle's height and angle; measured, as makers do not publish them
        q("$t.head.nozzle.angle", Dimension.ANGLE, "the nozzle's cone: the angle of its side from horizontal (a surface no steeper than it clears the cone); needed by curved layers", feeds = true)
        q("$t.head.nozzle.height", Dimension.LENGTH, "how far the nozzle tip sits below the lowest other part of the loaded head (heater block, sock, duct, carriage); needed by curved layers", feeds = true)
        q("$t.head.nozzle.x", Dimension.LENGTH, "the nozzle's position within the head along X: its distance from the head's edge on the machine's -X side (left on a front-left origin)", feeds = true)
        q("$t.head.nozzle.y", Dimension.LENGTH, "the nozzle's position within the head along Y: its distance from the head's edge on the machine's -Y side (front on a front-left origin)", feeds = true)
        q("$t.head.size.x", Dimension.LENGTH, "the loaded head's size along the machine's X axis, seen from below (its outline, a rectangle)", feeds = true)
        q("$t.head.size.y", Dimension.LENGTH, "the loaded head's size along the machine's Y axis, seen from below", feeds = true)
        // (the machine's head.<key>.head.radius and gantry_height, so that "head" means only the loaded head)
        q("$t.head.radius", Dimension.LENGTH, "the loaded head as a circle around the nozzle, its radius, for clearance (not yet used)")
        q("$t.gantry.height", Dimension.LENGTH, "how far the gantry sits above the nozzle tip, for clearance (not yet used)")
        q("$t.temperature.max", Dimension.TEMPERATURE, "hottest its nozzle may be set (the machine maker's figure): above it plymeld warns", feeds = true)
        q("$t.pressure_advance", Dimension.TIME, "pressure advance it prints with (Klipper's, in seconds, e.g. \"0.035 s\") unless the filament sets its own; unset = the machine's (hardware.machine.<key>.pressure_advance)", feeds = true)
        q("$t.heat.time", Dimension.TIME, "on a multi-nozzle machine, heat the next nozzle this long (print time) before its change; unset = its machine model's toolhead's (hardware.model.<key>.toolhead.<key>.heat.time), else no preheat (the change waits for the nozzle)", feeds = true)
        q("$t.extrude.section.max", Dimension.AREA, "the largest cross-section a move may extrude (filament per mm of head motion times the filament's cross-section) as its firmware checks it: Klipper's max_extrude_cross_section, whose own default is 4 times the nozzle diameter squared. A restore spread over a path (filament retract.return.distance) stays within it; unset = not known, and every restore stands still", feeds = true)
        for (a in listOf("x", "y", "z")) q("$t.offset.$a", Dimension.LENGTH, "its nozzle's offset from the first tool's, $a (not yet used)")
        // ---- hardware: filaments
        val f = "hardware.filament.<key>"
        d("$f.like", Type.Ref("filament"), "start from another filament's values")
        d("$f.kind", Type.Text, "PLA, PETG, TPU, ...")
        d("$f.color", Type.Text, "color as #RRGGBB", feeds = true)
        q("$f.diameter", Dimension.LENGTH, "filament diameter", feeds = true)
        q("$f.density", Dimension.DENSITY, "density (mass per volume), e.g. 1.24 g/cm3: the decisions compare material by mass with it (job.priorities.material); unset = by volume", feeds = true)
        d("$f.cost", Type.Text, "cost, e.g. 20 /kg (not yet used)")
        q("$f.temperature.print", Dimension.TEMPERATURE, "nozzle temperature", feeds = true)
        q("$f.temperature.first_layer", Dimension.TEMPERATURE, "nozzle temperature on the first layer", feeds = true)
        q("$f.temperature.min", Dimension.TEMPERATURE, "lowest nozzle temperature it is meant to print at (the spool's label): below it plymeld warns; a calibration plate's default range starts here", feeds = true)
        q("$f.temperature.max", Dimension.TEMPERATURE, "highest nozzle temperature it is meant to print at (the spool's label): above it plymeld warns; a calibration plate's default range ends here", feeds = true)
        q("$f.temperature.idle", Dimension.TEMPERATURE, "temperature of a parked nozzle on a multi-nozzle machine")
        q("$f.bed.<key>.temperature.print", Dimension.TEMPERATURE, "this filament's own bed temperature on a build plate (by the plate's key) after the first layer, over the plate's for its kind")
        q("$f.bed.<key>.temperature.first_layer", Dimension.TEMPERATURE, "this filament's own first-layer bed temperature on a build plate; unset = its bed temperature there (temperature.print)")
        q("$f.bed.<key>.first_layer.speed", Dimension.SPEED, "the first-layer speed with this filament on this build plate (by the plate's key), over the filament's and the machine's and over the plate's first-layer window, which then gives only the first layer's height (a warning and the filament's or machine's values when this speed is past the window's fastest good one)")
        q("$f.melt.flow.max", Dimension.FLOW, "fastest it melts; every move is capped to it. Its own value is the default for every machine and tool; one set for the machine (machine.<key>.melt.flow.max) or the tool (tool.<key>.melt.flow.max) wins over it", feeds = true)
        // the melt rate depends on the hot end as much as the plastic, so it is scoped to the machine, with the tool as an
        // override: read through Config.filamentEntry, the tool's over the machine's over the filament's own
        q("$f.machine.<key>.melt.flow.max", Dimension.FLOW, "fastest it melts on one machine (by the machine's key), over its own melt.flow.max; a tool's (tool.<key>.melt.flow.max) wins over it")
        q("$f.tool.<key>.melt.flow.max", Dimension.FLOW, "fastest it melts in one tool (by the tool's key), over the machine's (machine.<key>.melt.flow.max) and its own")
        q("$f.motion.first_layer.speed", Dimension.SPEED, "speed of everything on the first layer with this filament, over the machine's default (hardware.machine.<key>.motion.first_layer.speed); unset = the machine's; its first-layer window on the build plate in use, if any, wins over it")
        // the first-layer window (FirstLayerWindow): from and bore declared before the tables' wildcard, which they would match too
        // any technique value of the filament's own (technique values as config per filament, taken
        // over through like, tweaked per part): the same paths as plan.technique.<technique>.<setting>, read by the
        // setting's own type; ConfigSetup takes them over the bundled default and the job's general value, under an object's or a
        // part's own value and under a plan value a user file sets (the first-layer speed's rule)
        for (depth in listOf("<key>.<key>", "<key>.<key>.<key>")) {
            d("$f.technique.$depth", Type.Text, "the filament's own value of plan.technique.<technique>.<setting> at this path (e.g. technique.ironing.flow = \"0.12\", measured on its plate or set by hand), in the setting's own unit: over the bundled default and the job's general value, under a value set for an object or a part and under a plan value a config file of yours sets; a tool's (tool.<key>.technique.…) and a machine's (machine.<key>.technique.…) over it; taken over through like")
            d("$f.machine.<key>.technique.$depth", Type.Text, "the filament's value of plan.technique.<technique>.<setting> on one machine (by the machine's key), over its own technique.… value; a tool's (tool.<key>.technique.…) wins over it")
            d("$f.tool.<key>.technique.$depth", Type.Text, "the filament's value of plan.technique.<technique>.<setting> in one tool (by the tool's key), over the machine's (machine.<key>.technique.…) and its own")
        }
        d("$f.first_layer.window.<key>.from", Type.Text, "the first-layer window on a build plate (by its key): what it was measured with (the plate's g-code, when it printed)")
        q("$f.first_layer.window.<key>.bore", Dimension.LENGTH, "the nozzle bore the first-layer window was measured with; it is used only for a tool of that bore")
        q("$f.first_layer.window.<key>.<key>.height.min", Dimension.LENGTH, "the first-layer window at one nozzle temperature (the key, e.g. \"210 C\"): the lowest nozzle height graded good on the first-layer plate, every lower one bad; the first layer's height unless the plan locks it (raised to what the tool lays as a first layer, and not used when it is above that)")
        q("$f.first_layer.window.<key>.<key>.speed.max", Dimension.SPEED, "the first-layer window at one nozzle temperature: the fastest first-layer speed graded good on the first-layer plate; the first layer's speed unless the plan locks it or the filament sets one for the build plate (bed.<key>.first_layer.speed)")
        q("$f.overhang.share",Dimension.RATIO, "how far a line may hang past the one below and hold, as a share of its width, at overhang.fan and overhang.speed; the flattest face that holds follows from the layer height", feeds = true)
        q("$f.overhang.fan", Dimension.RATIO, "part fan while a line hangs past the one below (the cooling overhang.share holds at)", feeds = true)
        q("$f.bridge.span.max", Dimension.LENGTH, "the longest span it bridges cleanly between two anchors; a bridge longer than this needs support under its middle. The bundled value is a stand-in until measured", feeds = true)
        q("$f.overhang.speed", Dimension.SPEED, "fastest a line that hangs past the one below prints (the speed overhang.share holds at); unset = not known", feeds = true)
        q("$f.flow", Dimension.RATIO, "extrusion multiplier (calibration)", feeds = true)
        q("$f.pressure_advance", Dimension.TIME, "pressure advance (Klipper's, in seconds, e.g. \"0.035 s\") for this filament, overriding the tool's (hardware.tool.<key>.pressure_advance)", feeds = true)
        d("$f.cooling.slowdown.enabled", Type.Bool, "slow down layers faster than the minimum layer time", feeds = true)
        q("$f.cooling.layer.time.min", Dimension.TIME, "an object's layer gets at least this long before its next layer goes on it: its own paths slowed when what prints in between (the rest of its layer, other objects' layers) does not fill it", feeds = true)
        q("$f.cooling.speed.min", Dimension.SPEED, "never slow below this for cooling", feeds = true)
        q("$f.cooling.fan", Dimension.RATIO, "part fan, as a share of full speed", feeds = true)
        q("$f.cooling.fan_off_layers", Dimension.COUNT, "part fan off for this many first layers", feeds = true)
        q("$f.retract.length", Dimension.LENGTH, "filament pulled back before a travel", feeds = true)
        q("$f.retract.speed", Dimension.SPEED, "retraction speed", feeds = true)
        q("$f.retract.return.speed", Dimension.SPEED, "speed pushing it back", feeds = true)
        q("$f.retract.return.distance", Dimension.LENGTH, "the push back after a travel spread over the path's first this many mm, laid as the head moves rather than standing still at the path's start (a blob there); as much of it as the tool's extrude.section.max allows over that length, the rest standing still first; unset = all standing still", feeds = true)
        q("$f.retract.coast.distance", Dimension.LENGTH, "coasting: the last this many mm of a path before a retraction laid with no filament pushed, the pressure left in the nozzle laying them (less ooze at the path's end); a path no longer than this is laid whole; unset = none", feeds = true)
        q("$f.retract.travel.min", Dimension.LENGTH, "shorter travels do not retract (nor lift)", feeds = true)
        // the lift goes with the retraction, as in other slicers (a printer's extruder value a filament may override there):
        // what a travel must clear depends on what the plastic does (oozing, curling) and the nozzle
        q("$f.retract.z_hop", Dimension.LENGTH, "how far the nozzle lifts for a travel that retracts", feeds = true)
        d("$f.retract.return.inside", Type.Bool, "after a retracted travel into a closed outer wall, the head comes down one line width inside the wall's start, restores the retraction there, and moves onto the start with nothing pushed: the push's blob lands on hidden material, not on the visible face where the seam (a scarf seam's ramp at its thinnest) starts. Not with hardware.filament.<key>.retract.return.distance (the restore then spreads over the path's start). Bundled: true", feeds = true)
        d("$f.retract.wipe.inward", Type.Bool, "a wall loop's wipe (hardware.filament.<key>.retract.wipe.enabled), the outer wall's and the inner walls', steps one line width inside the wall first, onto hidden material, then runs back along it there, all of the retraction spread over its moves (none of retract_before standing where the loop ended): along the outer wall, the wipe's last ooze lay on the visible face; at the end of the wall beside it, the retraction stood just behind the face and showed through. Bundled: true", feeds = true)
        d("$f.retract.wipe.enabled", Type.Bool, "wipe along the path while retracting", feeds = true)
        q("$f.retract.restart_lag", Dimension.LENGTH, "how far a line prints starved after a retraction before the flow is back: what the restart measure (restart_starved) counts per visible path started after a retraction; a placeholder until measured", feeds = true)
        q("$f.retract.wipe.distance", Dimension.LENGTH, "length of the wipe", feeds = true)
        q("$f.retract.wipe.speed", Dimension.SPEED, "speed of the wipe", feeds = true)
        q("$f.retract.wipe.retract_before", Dimension.RATIO, "share of the retraction done before the wipe", feeds = true)
        q("$f.purge.volume", Dimension.VOLUME, "plastic purged after a change to this filament", feeds = true)
        // ---- model: refinements of the objects given with --model
        val o = "model.object.<key>"
        d("$o.from", Type.Text, "the object's name in its model file, when the key differs")
        q("$o.count", Dimension.COUNT, "how many to print")
        q("model.count", Dimension.COUNT, "how many of every object to print")
        q("$o.part.<key>.slot", Dimension.COUNT, "the slot this part takes: a number of the machine's loadout, a tool's index, or a slot's (over what the model says and over the slot map)")
        q("model.slots.<key>", Dimension.COUNT, "the slot map: the loadout's slot that prints the parts the model file puts on filament <key> (its number; \"*\" = every number), e.g. model.slots = { 4 = 1, 5 = 2 }, or { \"*\" = 1 } to print a multi-color model in one filament; a number not in the map prints from the model's own")
        q("$o.slots.<key>", Dimension.COUNT, "this object's slot map, over model.slots: the slot that prints its parts the model file puts on filament <key> (\"*\" = every number)")
        // a section of the object printed on its own: a sample of a larger print (a face, a detail) without the hours of the whole
        d("$o.section.min", Type.Point(Dimension.LENGTH), "print only the part of the object inside a box: the box's least corner, in the model's own coordinates as its file gives them; with max. What its cut faces are is section.faces; every copy of the object is cut alike")
        d("$o.section.max", Type.Point(Dimension.LENGTH), "the section's box's greatest corner (above min on every axis), in the model's own coordinates")
        d("$o.section.faces", Type.Choice(listOf("open", "closed")), "a section's cut faces. open (unset): the whole object is planned, posed, and placed as it would be without the section, and only its lines inside the box are laid, up to the box's top: the section prints as that part of the whole print would (the same walls, seams, infill, speeds, and filament changes), with no walls, top, or bottom added where it is cut, so the infill shows there; the box must reach down to the model's bottom, and the object must print on flat layers. closed: the model is cut to the box as it is read, each cut face closed, so the section is planned, laid out, sliced, and checked as an object of its own (its bottom on the bed, walls and top surfaces on its cut faces); a part with nothing in the box is left out; the model must be a closed surface where the box cuts it")
        q("$o.place.x", Dimension.LENGTH, "locked position, X (not yet used)")
        q("$o.place.y", Dimension.LENGTH, "locked position, Y (not yet used)")
        q("$o.place.turn", Dimension.ANGLE, "locked turn about Z (not yet used)")
        // ---- job: what the user wants
        d("job.machine", Type.Ref("machine"), "the machine that prints the job (its loadout supplies the plate, tools, and filaments); `--machine` wins over it; unset with exactly one machine defined = that one")
        for (g in GOALS) q("job.priorities.$g", Dimension.WEIGHT, "how much $g matters (relative): ${GOAL_MEANING.getValue(g)}")
        for (m in MEASURES) q("job.priorities.measure.$m", Dimension.WEIGHT, "the measure $m's share of its goal's weight, relative to the goal's other measures (default 1; the bundled config gives layer_lines 4; 0 drops it; plymeld plan lists each decision's measures and their goals)")
        q("job.requirements.time.max", Dimension.TIME, "longest acceptable print (not yet used)")
        // the locks: the surface technique locked on each kind of exposed surface and the seam style
        // locked for the wall loops, each a lock of the technique it names (its own values: plan.technique.<name>)
        d("plan.faces.flat_top.technique", Type.Choice(listOf("ironing", "as_laid")), "the surface technique locked on the flat tops: ironing = smooth, the nozzle passed over them again laying almost no plastic (plan.technique.ironing); as_laid = as the volume technique lays them. Unset = as laid")
        d("plan.faces.sloped_top.technique", Type.Choice(listOf("z_antialiasing", "as_laid")), "the surface technique locked on the sloped tops: z_antialiasing = the stair-steps removed: in flat layers, where the model's top lies within a layer, its lines are lowered onto the top with less plastic (never thinner than the tool's thinnest layer, hardware.tool.<key>.layer.height.min), and the layer reaches out to where the top is that far above its bottom; curved and lifted layers follow their own surfaces; as_laid = as laid. Unset = as laid (Z anti-aliasing until a print decides it)")
        d("plan.faces.side.technique", Type.Choice(listOf("fuzzy_skin", "as_laid")), "the surface technique locked on the walls: fuzzy_skin = rough, jittered in and out on the walls plan.technique.fuzzy_skin.where names; as_laid = as laid. Unset = as laid")
        d("plan.faces.under.technique", Type.Choice(listOf("wave_overhang", "as_laid")), "the surface technique locked on the undersides over air: wave_overhang = each flat layer's bottom over air that hangs off one side grown outward from the supported material beside it, bead by bead, each bead laid against the one before at plan.technique.wave_overhang's spacing, flow, and speed (a span, every bridge line landing on what is printed at both ends, stays a bridge; an area with support under it is the support's; what no front reaches is bridged); as_laid = bridged, as before. Bundled: wave_overhang (wave overhang, support, or nothing for an underside hanging off one side; never bridged)")
        // a surface technique limited by height: the quad tube's top ring alone, not its
        // helices' lower edges on every layer
        q("plan.faces.under.from", Dimension.LENGTH, "where the underside surface technique starts: only layers whose top is above this height (from the object's bottom, as printed) get it; unset = from the bottom", feeds = true)
        q("plan.faces.under.to", Dimension.LENGTH, "where the underside surface technique ends: only layers whose bottom is below this height (from the object's bottom, as printed) get it; unset = to the top", feeds = true)
        d("plan.seam", Type.Choice(listOf("corners", "back", "least_visible")), "where every wall loop starts (its seam), a scarf seam's too: corners = every loop starts at a corner when it has one (inside corners first), each layer's seam nearest the one below (with none below: the sharpest corner, else the back-most point); back = every loop starts at its back-most point; least_visible = every loop starts at the held-up point the seam measure counts least (its visibility, turned away from plan.view_from's viewer or an underside, times method.quality.seam.corner_share in a corner), ties to the point the viewer sees least first (a turned-away face before a front corner), then to a corner, then to the seam below, then the back; the outer walls' seams planned up the wall, over each run of layers a loop continues through, as the line of points that costs the seam measure least in all (each seam as seen, and each move from the seam below), so a move to a less-seen side is paid once, not judged layer by layer. Either way, a point on an edge of the layer that hangs over the layer below (where the overhang slow-down starts) only when the loop has no other. Unset = decided per object by the job's weights (seam_scar, with whether scarf seams are on where plan.technique.scarf_seam.enabled is unset)", feeds = true)
        q("plan.pause.<key>.at", Dimension.LENGTH, "pause before the first layer whose top is at or above this height")
        d("plan.pause.<key>.before", Type.Text, "pause before an object or part (by key) first prints")
        d("plan.pause.<key>.after", Type.Text, "pause after an object or part (by key) last prints")
        // a region of an object, by geometry, never by index: a box, a height range, the faces it holds, or several of these
        // together (a line is in the region when every one given holds); the values under it apply to the lines in it
        val rg = "plan.object.<key>.region.<key>"
        d("$rg.min", Type.Point(Dimension.LENGTH), "a region of the object: the least corner of its box, in the model's own coordinates as its file gives them (turned with the model's pose: the region is the turned box's bounding box); with max. Unset (both) = no box: the height range and the faces alone")
        d("$rg.max", Type.Point(Dimension.LENGTH), "the region's box's greatest corner (above min on every axis), in the model's own coordinates")
        q("$rg.from", Dimension.LENGTH, "the region's bottom as printed: lines at or above this height from the object's bottom; unset = from the bottom")
        q("$rg.to", Dimension.LENGTH, "the region's top as printed: lines at or below this height from the object's bottom; unset = to the top")
        d("$rg.facing", Type.Choices(listOf("flat_top", "sloped_top", "side", "under")), "the lines in the region by the faces they lay, in plan.faces's words: side = wall lines, flat_top and sloped_top = top surface lines and their ironing (a line does not say whether its top is flat or sloped: either selects both), under = bottom surfaces, bridges, and wave overhangs; unset = every line. Not read with a support rule (support holds downward faces only)")
        // the user's support rules: where support must go, and where it must not, whatever the switch or the weights decide
        d("$rg.support", Type.Choice(listOf("always", "never")), "support under the region's downward faces whatever plan.support.enabled or the weights decide: always = every downward face in it gets support, whether or not it needs it (a requirement: refused with the switch off, and where nothing in the region can be supported); never = no support under them (their droop counted as unsupported area); unset = the object's own rule")
        // a planned insert: a socket the print pauses at so an object (a steel ball, a magnet, a nut) is placed
        // in it, the next layer planned onto the object's top; a box in the model's own coordinates, as a locked spot
        val ins = "plan.insert.<key>"
        d("$ins.min", Type.Point(Dimension.LENGTH), "a planned insert: a socket the print pauses at so an object is placed in it, the layer over it printed onto the object's top (no bridge, no support in the socket); the box's least corner, in the model's own coordinates; the pose must be locked (plan.pose = \"as-given\" or plan.object.<key>.pose.turn) and keep the socket opening upward")
        d("$ins.max", Type.Point(Dimension.LENGTH), "the insert's box's greatest corner (above min on every axis), in the model's own coordinates")
        q("$ins.top", Dimension.LENGTH, "the insert's top once placed, in the model's own z (unset: the box's top, max's z): the pause comes before the first layer whose bottom is at or above it, and that layer's bottom over the socket is laid onto the insert")
        d("job.allowed_techniques", Type.Choices(listOf("flat")), "techniques the search may use: flat (flat layers) is the only one it searches for now; curved, underside, and bridge are locked by a band (plan.object.<key>.band.<key>.technique). Which heights flat layers may use: plan.technique.flat.heights")
        // the look of a curved top: horizontal lines following the curve, or concentric rings with their stair-steps; no
        // preference by default
        d("job.faces.sloped_top.look", Type.Choice(listOf("no_preference", "lines_follow_curve", "concentric_steps")),
            "how a rounded top should look where a curved island could print it: no_preference (unset) = the job's weights decide per island (its stair-step, border, and time against flat layers'); lines_follow_curve = every island the survey finds, unweighed (its lines following the curve, e.g. horizontal lines over a sphere's top); concentric_steps = no curved island (flat layers: concentric rings with their stair-steps). An island the plan locks is printed either way", feeds = true)
        d("job.part_overlap", Type.Choice(listOf("started_first", "lower_filament", "higher_filament", "earlier_part", "later_part")), "where parts overlap, who prints the shared volume (started_first: the part that starts lowest, so an embedded part prints only where it comes out)", feeds = true)
        // ---- plan: decided unless locked
        d("plan.pose", Type.Choice(listOf("derive", "as-given")), "how each object sits: decided, or the model's own orientation")
        d("plan.placement", Type.Choice(listOf("center", "as-given")), "where objects sit")
        d("plan.objects.printing", Type.Choice(listOf("together", "one_at_a_time")), "objects printed together or one after another")
        d("plan.objects.order", Type.Choice(listOf("shortest_first", "as_given")), "the order objects printed one at a time (plan.objects.printing = one_at_a_time) go in: shortest_first (unset) = the shortest first; as_given = the order the objects are given (the models in the order loaded); the head's reach and the nozzle-to-rod height are checked against the objects already printed either way")
        // the arched-bridge calibration plate: every foot first, at one temperature, then the bridges hot to cold, one
        // temperature at a time
        d("plan.bridge_order", Type.Choice(listOf("by_height", "hot_to_cold", "one_at_a_time")), "objects printed together: by_height (unset) = every object's layers merged by height; hot_to_cold = every object's layers below its lifted bridge first (merged by height, at the print temperature), then the lifted bridges one bridge temperature at a time, hottest first (objects by plan.technique.bridge.temperature, unset = the print temperature), the head lifted clear of everything printed between groups: one temperature change per group, each a cooling; one_at_a_time = the layers below every lifted bridge first, then each object's bridge whole, one object after another from the side the head reaches least (the bridged objects' tools' head.size / head.nozzle.x, else their machine model's toolhead; with neither, from +X), so it never passes over a finished bridge; the layout must leave room", feeds = true)
        d("plan.object.<key>.pose.turn", Type.Numbers(9), "the object's locked orientation: a rotation, row by row (it is then set on the bed as placement says)")
        d("plan.object.<key>.pose.label", Type.Text, "what the locked orientation is, in words (for reading)")
        d("plan.object.<key>.band.<key>.technique", Type.Choice(listOf("flat", "curved", "underside", "bridge")),
            "the technique of one height band (flat: flat layers, which must say band.<key>.heights; curved: the top band only, its layers following the top; underside: the whole object, its layers following the underside; bridge: the whole object, the parts that do not reach the bed printed after what holds them up, along their underside; these three locked only for now)")
        d("plan.object.<key>.band.<key>.heights", Type.Choice(listOf("one", "follow")),
            "how a flat band sets its layers' heights, required on every flat band: one = every layer of the band band.<key>.layer.height thick (or the object's plan.technique.flat.layer.height where the config sets it); follow = each layer as thick as the surfaces it crosses allow within the tool's thinnest and thickest layer (plan.technique.flat.step.max), with no layer.height. No derive: bands lock the object's plan, so nothing is weighed per band (leave the bands out for the search to weigh both, plan.technique.flat.heights)")
        q("plan.object.<key>.band.<key>.layer.height", Dimension.LENGTH,
            "a flat band of one height's layer height (band.<key>.heights = \"one\"): every layer of the band this thick. Every flat band of one height in an object has the same height, for now")
        d("plan.object.<key>.band.<key>.depth", Type.Choice(listOf("auto")),
            "a curved band's depth: auto = plymeld starts it as high as keeps every layer at least the tool's thinnest, flat layers below; the curved band on its own, no from or to")
        q("plan.object.<key>.band.<key>.from", Dimension.LENGTH, "where the band starts, from the object's bottom")
        q("plan.object.<key>.band.<key>.to", Dimension.LENGTH, "where the band ends (leave it out for the top)")
        // a curved island the plan locks
        val isl = "plan.object.<key>.island.<key>"
        for (a in listOf("x", "y")) q("$isl.$a", Dimension.LENGTH, "a curved island (locked only, for now): a cap of the top (sloping 1 deg to the nozzle angle, " +
            "joined where the top is continuous) printed in its own layers over the flat ones, its layers dipping to follow the cap (by the nozzle's height at most), " +
            "merged by height with the other layers; the island named by a point on its cap, its $a from the posed object's corner (its least $a). The object's other " +
            "layers must be flat (no band)")
        q("$isl.start", Dimension.LENGTH, "where the island's own layers start, from the object's bottom (snapped down to the top of the flat layer under it); unset: the start whose layers follow the most of the cap")
        q("$isl.layer.height", Dimension.LENGTH, "the island's own layer height (where its layers do not dip; thinner where they do, never under the tool's thinnest): its height, from its start as printed to its top, is split into equal layers as near this as keeps every layer within the tool's thinnest and thickest; unset: the thinnest that keeps every layer at least the tool's thinnest, and no thinner than the flat layers")
        // a support spot the plan locks (written by `plymeld plan -o` from what the search chose)
        val ss = "plan.object.<key>.support.spot.<key>"
        d("$ss.min", Type.Point(Dimension.LENGTH), "a support spot the plan locks (what `plymeld plan -o` wrote of the search's choice): the faces too steep to hold themselves up within the box get tree support, nothing else does; the box's least corner: x and y from the posed object's corner (its least x and y), z from the object's bottom as posed")
        d("$ss.max", Type.Point(Dimension.LENGTH), "the locked support spot's greatest corner (above min on x and y, at or above it on z: a flat underside), in the same frame")
        d("$ss.order", Type.Choice(listOf("interleaved", "first")), "the print order of the spot's support column beside the model's layers: interleaved = layer by layer with them; first = the column finished before the model's layers beside it start (only where it stays under the nozzle tip's protrusion, hardware.tool.<key>.head.nozzle.height). Unset = interleaved")
        d("plan.source_fingerprint.model.<key>", Type.Text, "fingerprint of an object's geometry when the plan was made")
        d("plan.source_fingerprint.config.<key>", Type.Text, "fingerprint of one section (hardware, model, job, method) of the config the plan was made from")
        // flat layers: uniform and adaptive layers as one technique
        d("plan.technique.curved.top_order", Type.Choice(listOf("one_way", "uphill")), "the order of a curved band's top surface lines: one_way (unset) = one sweep across the surface, as any top, so on one side of a peak each new line goes down beside the one before, below it; uphill = the lines split where the surface is highest, each side laid from its lowest line up to it, so each new line goes down beside the one before on its low side (plate 8's solid arch came out rough on the half its top was swept down)", feeds = true)
        q("plan.technique.flat.layer.height", Dimension.LENGTH, "flat layers: the layer height where they keep one height (and of the flat layers under a curved band or holding up a lifted bridge); decided by the search over the tool's thinnest to thickest layer unless set in a config (the bundled value is only the stand-in other values are worked out from)", feeds = true)
        q("plan.technique.flat.step.max", Dimension.LENGTH, "flat layers following the surfaces: the deepest stair-step a layer may leave (unset: the layer height)", feeds = true)
        q("plan.technique.flat.height_ratio", Dimension.RATIO, "the transition between layer heights: no layer (after the first) more than this much thicker than the one below it, and the layers before a band whose layers start thinner stepping down by about this much each (\"1.5\": a 0.08 mm band meets a 0.28 mm one through 0.12, 0.18, 0.27); \"0\" = no limit, a jump at the band's boundary", feeds = true)
        q("plan.technique.flat.core.layers", Dimension.COUNT, "the hidden core in thicker layers: every this many layers, the sparse infill those layers share is laid once, on the last of them, the layers' height thick (within the tool's thickest layer); each layer's sparse infill outside what the group shares is laid on its own layer. \"1\" = every layer its own (off). Bundled: as many layers as fit the tool's thickest layer", feeds = true)
        d("plan.technique.flat.heights", Type.Choice(listOf("derive", "one", "follow")),
            "how flat layers set their heights: one = one layer height for the whole object; follow = every layer as thick as the surfaces it crosses allow (needs the tool's thinnest and thickest layer, and no fuzzy skin); derive (or unset) = the search weighs both, and the bands flat layers' survey proposes (following the surfaces there, one height elsewhere)")
        // plan.layer: what every flat layer is made of (walls, skin, interior, surfaces, bridges), for
        // every flat-layer technique. INHERITED values are today's
        // pipeline's, being derived, decided, or deleted in the order of that note.
        val pl = "plan.layer"
        q("$pl.walls.thickness", Dimension.LENGTH, "INHERITED: wall thickness (the number of wall lines follows from the line width)")
        d("$pl.walls.style", Type.Choice(listOf("fixed_width", "variable_width")), "INHERITED: wall lines of one width, or variable width in thin areas", feeds = true)
        q("$pl.walls.outer.line.width", Dimension.LENGTH, "INHERITED: outer wall line width", feeds = true)
        q("$pl.walls.inner.line.width", Dimension.LENGTH, "INHERITED: inner wall line width", feeds = true)
        d("$pl.walls.order", Type.Choice(listOf("outer_first", "inner_first", "inner_outer_inner")), "the order each contour's walls are laid in: outer_first = the outer wall, then inward (the layer's first line and its un-retraction land on the visible face); inner_first = the innermost first, the outer wall last, laid against the wall beside it (an overhanging outer wall leans on it); inner_outer_inner = the walls past the one beside the outer wall, then the outer wall, then that one: the first line and the travel's arrival land on hidden walls and the outer wall is laid free; with fewer than three walls, or where the outer wall hangs out past the layer below (as far as the overhang slow-down starts), inner_first. Bundled: inner_outer_inner", feeds = true)
        q("$pl.top.thickness", Dimension.LENGTH, "INHERITED: solid thickness under a top", feeds = true)
        q("$pl.bottom.thickness", Dimension.LENGTH, "INHERITED: solid thickness over a bottom", feeds = true)
        q("$pl.infill.density", Dimension.RATIO, "INHERITED: share of the interior filled", feeds = true)
        d("$pl.infill.pattern", Type.Choice(listOf("rectilinear", "grid", "cubic", "gyroid")), "INHERITED: the interior's pattern (gyroid: curves, the gyroid surface's cross-section; the interior angle does not apply to it)", feeds = true)
        q("$pl.infill.line.width", Dimension.LENGTH, "INHERITED: interior line width", feeds = true)
        q("$pl.infill.angle", Dimension.ANGLE, "INHERITED: interior line direction", feeds = true)
        q("$pl.infill.chord_tolerance", Dimension.LENGTH, "INHERITED: how far a curved interior line (the gyroid's) may stray from the true curve between two of its vertices: the traced curve is simplified to it before it is laid. Unset (bundled) = laid as traced, where the arc fitter turns its runs into arcs; set it for a printer without arcs (fewer, longer straight segments). With arcs on, simplifying first is slower: the simplified curve no longer fits as arcs (a 9 h print's gyroid 843,112 arcs -> 15,215, predicted 14 min slower). Not the outline tolerance (method.slicing.tolerance)", feeds = true)
        // a constant of plymeld's before it was a setting: the bundled value carries where it came from
        q("$pl.infill.wall_overlap", Dimension.RATIO, "how far an interior line's end reaches into the walls past bead contact, as a share of its width (a line end touches a wall only with its rounded cap)", feeds = true)
        q("$pl.infill.narrow.cells", Dimension.RATIO, "a sparse piece narrower than this many cells of its pattern is narrow (default 1)", feeds = true)
        d("$pl.infill.narrow.fill", Type.Choice(listOf("loop", "solid")), "what a narrow sparse piece gets: one loop (default) or solid", feeds = true)
        q("$pl.solid.line.width", Dimension.LENGTH, "INHERITED: internal solid line width", feeds = true)
        d("$pl.top.pattern", Type.Choice(listOf("monotonic", "rectilinear", "concentric-outside-in", "concentric-inside-out", "archimedeanchords")), "INHERITED: top surface pattern (concentric-outside-in: loops following the outline, each the one outside it moved in; concentric-inside-out: round rings around the surface's middle, arcs where a full circle does not fit)", feeds = true)
        d("$pl.top.under_parts", Type.Bool, "a top surface that is mostly exposed runs under the other parts standing on it: its pattern unbroken, as if nothing stood there", feeds = true)
        d("$pl.bottom.pattern", Type.Choice(listOf("monotonic", "rectilinear", "concentric-outside-in", "concentric-inside-out", "archimedeanchords")), "INHERITED: bottom surface pattern (as top_pattern)", feeds = true)
        q("$pl.top.line.width", Dimension.LENGTH, "INHERITED: top surface line width", feeds = true)
        q("$pl.bridge.flow", Dimension.RATIO, "INHERITED: bridge extrusion multiplier", feeds = true)
        val mo = "plan.mechanism.motion"
        q("$mo.visible.speed", Dimension.SPEED, "speed of visible lines: outer walls, top and bottom surfaces; a value in a config of yours wins over the machine's (hardware.machine.<key>.motion.visible.speed, from its motion plate); plymeld's bundled value is only the stand-in for a machine that sets none (until decided from the quality weight)", feeds = true)
        d("$mo.inner_walls.as_visible", Type.Bool, "INHERITED: inner walls at the visible lines' speed and acceleration (visible.speed, visible.acceleration), so the ripple of a wall laid fast right behind the outer wall does not show through it; false = at the hidden lines' (the machine's printing speed and acceleration)", feeds = true)
        q("$mo.visible.acceleration", Dimension.ACCEL, "acceleration of visible lines; the rest print at the machine's printing acceleration; a value in a config of yours wins over the machine's (hardware.machine.<key>.motion.visible.acceleration); plymeld's bundled value is only the stand-in", feeds = true)
        // not fed directly: its setting (of the same id) is made from this, the filament's, the machine's, and the filament's
        // first-layer window ([made], ConfigSetup.derived)
        q("$mo.first_layer.speed", Dimension.SPEED, "speed of everything on the first layer (adhesion): a value in a config locks it over the filament's first-layer window (hardware.filament.<key>.first_layer.window.<plate>), its motion.first_layer.speed, and the machine's (hardware.machine.<key>.motion.first_layer.speed); plymeld's bundled value is only the stand-in for a machine and filament that set none")
        q("$mo.bridge.speed", Dimension.SPEED, "bridge speed; a wall line slows toward it as more of it hangs over air", feeds = true)
        q("$mo.speed.max", Dimension.SPEED, "the fastest any line prints, whatever its own speed; set on a region (plan.object.<key>.region.<key>.mechanism.motion.speed.max) for the lines in it only, without laying the object in pieces; unset = no cap", feeds = true)
        q("$mo.acceleration.max", Dimension.ACCEL, "the most any line accelerates, whatever its own acceleration; set on a region for the lines in it only; unset = no cap", feeds = true)
        q("$mo.melt.buffer", Dimension.RATIO, "the share of the filament's maximum flow (melt.flow.max) kept unused: every move's flow is capped at the maximum flow less this share (10 % = capped at 90 % of it); the calibration tests that probe the limit itself (the melt plate's rows) are not; 0 % = no buffer; bundled 10 %", feeds = true)
        q("$mo.detour.margin", Dimension.LENGTH, "avoid_crossing: how far outside an island's outer wall a detour runs (beyond the wall's center line plus half its width); bundled 1 mm, a placeholder", feeds = true)
        q("$mo.detour.ratio.max", Dimension.RATIO, "avoid_crossing: a detour longer than this many times the straight travel plus detour.extra.max is not taken (the straight travel with its lift is); bundled 2, a placeholder", feeds = true)
        q("$mo.detour.extra.max", Dimension.LENGTH, "avoid_crossing: the length added to detour.ratio.max times the straight travel that a detour may still have; bundled 20 mm, a placeholder", feeds = true)
        q("$mo.detour.speck.ratio.max", Dimension.RATIO, "avoid_crossing: a travel to or from an outer-wall speck routed along the wall it lines (where combing finds no way inside) is not taken when longer than this many times the straight travel plus detour.speck.extra.max; bundled 5", feeds = true)
        q("$mo.detour.speck.extra.max", Dimension.LENGTH, "avoid_crossing: the length added to detour.speck.ratio.max times the straight travel that a route along the wall to or from a speck may still have; bundled 50 mm", feeds = true)
        q("$mo.detour.inset", Dimension.LENGTH, "avoid_crossing: how far inside an island's outer wall (its center line) a travel within the island runs; unset = the widest outer wall's line width on the layer. Where no route stays this far inside (material thinner than two insets), a half, a quarter, and an eighth of it are tried, so the route runs down the material's middle", feeds = true)
        d("$mo.avoid_crossing", Type.Bool, "travels go around the layer's other islands rather than straight over them (a string a travel draws then lies over nothing): every closed outer wall of the layer is an island, its convex hull detour.margin outside the wall; and a travel within one island stays inside it, detour.inset inside its outer walls (never out over an edge or across a notch in its outline; a travel to an outer-wall speck just past the walls, a line kept because it lines the outline, hops out to it only at the speck; where no route inside reaches a speck, the travel runs along the outer wall it lines, retracting as usual, within detour.speck.ratio.max and detour.speck.extra.max); an island the travel leaves or enters cannot be avoided; a detour or route longer than detour.ratio.max times the straight travel plus detour.extra.max is not taken", feeds = true)
        d("$mo.no_retract_in_island", Type.Bool, "no retraction and no lift for a travel whose straight path stays inside the island being printed (the piece of the layer it is on), crosses no wall line already laid on the layer nor any outer wall of the layer, and neither leaves nor enters a top surface or ironing (above the first layer); a travel that leaves the island, crosses a gap or a wall retracts and lifts as usual. Unset = decided per object by the plan on its planned layers, by the job's weights (the strings those travels risk against the restarts and the time of retracting them; plan and slice say which, `plan -o` writes it); true = on (a helix island's runs each retracted, lifted, and restored for 1-2 mm moves inside it); false = every travel over retract.travel.min retracts and lifts, except those plymeld keeps hidden inside the infill and the travel into an outer-wall speck (a wall line shorter than two of its widths) that passes these tests or is reached from its own outer wall in its island, then lifted over that wall but not retracted (a restart for a dot left a blob)", feeds = true)
        // not fed directly (ConfigSetup.derived): over the filament's own, for the scope it is set at (a
        // nozzle temperature per object: nothing calls for a limit)
        val te = "plan.mechanism.temperature"
        q("$te.print", Dimension.TEMPERATURE, "the nozzle temperature after the first layer, over the filament's temperature.print; set on an object or part (plan.object.<key>.mechanism.temperature.print) for that one only")
        q("$te.first_layer", Dimension.TEMPERATURE, "the nozzle temperature on the first layer, over the filament's temperature.first_layer (not changed by temperature.print)")
        // the part fan and the minimum layer time for an object or part over its filament's, not fed directly
        // (ConfigSetup.derived), as the nozzle temperature: what bond-first cooling sets, and what a config can set itself
        val co = "plan.mechanism.cooling"
        q("$co.fan", Dimension.RATIO, "the part fan for an object's or part's lines from its fan-on layer, over the filament's cooling.fan; set on an object or part (plan.object.<key>.mechanism.cooling.fan) for that one only. Its overhang lines keep the filament's overhang.fan")
        q("$co.layer.time.min", Dimension.TIME, "an object's or part's minimum layer time, over the filament's cooling.layer.time.min; set on an object or part for that one only")
        d("$co.slowdown.style", Type.Choice(listOf("hidden_first", "even")), "how a layer is slowed to its minimum layer time: hidden_first = the hidden lines first (inner walls, infill, support), each down to the minimum speed, the visible lines (outer walls, top and bottom surfaces) only for what they cannot give; even = every line by one factor", feeds = true)
        d("$co.dwell.enabled", Type.Bool, "a layer still short of its minimum layer time with every line at the minimum speed: true = the head waits out the rest, retracted, as dwell.style says; false = printed as it is", feeds = true)
        d("$co.dwell.style", Type.Choice(listOf("lift", "park")), "how the head waits: lift = straight up from where the layer ended, dwell.lift (within the machine's height), so it keeps its distance from the part and any ooze stays put; park = beside the layer, lifted clear, the nozzle where the head's edge just clears the layer's printed area on the nearest side within the bed (from hardware.tool.<key>.head.size and head.nozzle.x / head.nozzle.y; without them no wait, said in a comment)", feeds = true)
        q("$co.dwell.lift", Dimension.LENGTH, "how far the head rises for a lift dwell (dwell.style = lift)", feeds = true)
        q("$co.overhang_fan", Dimension.RATIO, "the part fan for an object's or part's lines over air (overhangs, bridges), over the filament's overhang.fan; set on an object or part for that one only. Over air the fan is the larger of this and cooling.fan")
        // bond-first cooling (strength where the layers' bond is weak): a lock, else weighed
        d("plan.bond_cooling", Type.Choice(listOf("on", "off")), "bond-first cooling for an object (plan.object.<key>.bond_cooling) or every object: on = its lines at plan.technique.bond_cooling's fan, minimum layer time, and nozzle temperature (the bond between layers before the filament's cooling); off = the filament's cooling. Unset = weighed per object by the job's weights: the bond (bond.strength, strength) against the cooling it gives up (cooling_shortfall, quality); only an object whose weakest bond is under method.strength.bond.enough_area gains anything, so the rest keep the filament's cooling")
        val bc = "plan.technique.bond_cooling"
        q("$bc.fan", Dimension.RATIO, "bond-first cooling's part fan for the object's lines; bundled half the filament's cooling.fan, a placeholder", feeds = true)
        q("$bc.layer.time.min", Dimension.TIME, "bond-first cooling's minimum layer time; bundled half the filament's, a placeholder", feeds = true)
        q("$bc.temperature", Dimension.TEMPERATURE, "bond-first cooling's nozzle temperature after the first layer, capped at the filament's temperature.max where set; bundled the filament's print temperature + 10 C, a placeholder", feeds = true)
        val ad = "plan.mechanism.adhesion"
        q("$ad.first_layer.height", Dimension.LENGTH, "INHERITED: first layer height; a value in a config locks it over the filament's first-layer window (hardware.filament.<key>.first_layer.window.<plate>); plymeld's bundled value is the stand-in without one", feeds = true)
        q("$ad.first_layer.line.width", Dimension.LENGTH, "INHERITED: first layer line width", feeds = true)
        d("$ad.brim.enabled", Type.Bool, "INHERITED: a brim around the first layer")
        d("$ad.brim.where", Type.Choice(listOf("outer")), "INHERITED: where the brim goes: outer = around the outside of the first layer")
        q("$ad.brim.width", Dimension.LENGTH, "INHERITED: brim width", feeds = true)
        q("$ad.brim.gap", Dimension.LENGTH, "INHERITED: gap between brim and object", feeds = true)
        // the prime line: plymeld's own, near the print, so it is easy to remember to peel off
        d("$ad.prime.enabled", Type.Bool,
            "a separate prime line before the first layer. Unset: on when prime.line is set, else off with a prime tower and on otherwise")
        d("$ad.prime.line", Type.Choice(listOf("near", "tab")),
            "where the prime line lies: near = alongside the first object, prime.gap from it; tab = the same on a peel tab, a partial brim joining it to the print (alongside the brim's outer edge when there is a brim). Unset: tab (near the model, with a brim, by default)")
        q("$ad.prime.length", Dimension.LENGTH, "the prime line's length", feeds = true)
        q("$ad.prime.gap", Dimension.LENGTH, "how far the prime line lies from the print (from the brim's outer edge on a tab)", feeds = true)
        // the lifted bridge, a technique (band technique "bridge"): its own values under plan.technique.bridge (they
        // were plan.technique.bridge). Its own speeds (the bridge is all of its layers; nothing overrides
        // them short of a risk to the printer); tacking its layers to its first: start a layer lower than usual, extrude a
        // little to fuse with the bridge's first layer, rise to the layer's height and go on, at the bridge's outer edges
        // first, then every few millimetres
        d("plan.technique.bridge.ramp_speeds", Type.Text, "an ARCHED lifted bridge's speeds over air in its layers after its first: its first layer's segments over the hollow at bridge.print.speed, the next layers' at these speeds in turn, in mm/s separated by commas (e.g. \"6, 8, 12, 24 mm/s\"), then each bridge.ramp_growth times the last, every one capped at the bridge speed (plan.mechanism.motion.bridge.speed), the ramp ending with the first that reaches it; the usual speeds after. Every segment over what holds the bridge up at the usual speeds throughout. Empty with ramp_growth \"1\" = no ramp: the usual speeds from the second layer. After a wave overhang's layer (its beads at wave_overhang.speed), the layers over the beads take the bridge's whole sequence: bridge.print.speed, then these, then the growth", feeds = true)
        q("plan.technique.bridge.ramp_growth", Dimension.RATIO, "after bridge.ramp_speeds, each layer's speed over air of an ARCHED lifted bridge this many times the last, up to the bridge speed; \"1\" or less = no growth: the usual speeds after the listed ones", feeds = true)
        q("plan.technique.bridge.temperature", Dimension.TEMPERATURE, "the nozzle temperature of an ARCHED lifted bridge's own layers (its first layer arching above or below straight between its supports by more than half a layer: an arch, a sag; not a flat, ramp, or valley bridge), waited for before them; the rest of the object (what holds the bridge up) at its print temperature, waited for again after; over plan.mechanism.temperature.print and the filament's. Unset: the print temperature", feeds = true)
        q("plan.technique.bridge.print.speed", Dimension.SPEED, "an ARCHED lifted bridge's first layer, where it is over air (tacks too), at this speed, its next layers stepping up to the bridge speed as bridge.ramp_speeds and ramp_growth say; what lies over the bridge's supports at the usual speeds: no slow-down for layer time, no floor, no overhang grading on the slow segments (the melt cap still applies: it only lowers a speed when the filament cannot melt fast enough). Unset: the usual speeds", feeds = true)
        q("plan.technique.bridge.travel.speed", Dimension.SPEED, "every head move that extrudes nothing in an ARCHED lifted bridge's layers (travels, lifts, the drops into tacks) at most this fast; not retractions and their restores, which keep the filament's speeds. Unset: the usual speeds", feeds = true)
        d("plan.technique.bridge.fill_angles", Type.Choice(listOf("span", "object")), "the direction of a lifted bridge's fill from its first layer up (its bottom, solid, sparse, and top fill): span (unset) = at its span's direction (as its first layer's bridge lines take it between its anchors) and at right angles to it, layer by layer, counted from its top so its top layer runs along the span, foot to foot; a grid's or cubic's sparse families from the span; object = the object's own fill directions (plan.layer.infill.angle, turned 90 deg layer by layer). With fill_along = true every fill but the top runs along the span either way", feeds = true)
        d("plan.technique.bridge.fill_along", Type.Bool, "a lifted bridge's layers all along its bridge: from its first layer up, every fill (bottom, solid) at the angle its first layer's bridge lines take; its top surface keeps its usual angle (\"Allow for non-parallel motion on the top/finishing layers\")", feeds = true)
        q("plan.technique.bridge.layer.height", Dimension.LENGTH, "a lifted bridge's own layer height, for an object with several lifted bridges (each printed after the layer it rests on): what holds them up keeps the object's layer height (the double helix's wall at 0.1 mm, its arches at 0.2). Unset: the object's layer height. A lone lifted bridge shares the object's layers: a different value is refused", feeds = true)
        d("plan.technique.bridge.top_layer", Type.Choice(listOf("normal", "bridge")), "an ARCHED lifted bridge's top layer (its last, when it has more than one): normal = at the usual speeds and the print temperature (waited for), ironing with it; bridge = at the bridge's own speeds and temperature, as its other layers", feeds = true)
        // the waved arch
        d("plan.technique.bridge.first_layer", Type.Choice(listOf("lines", "wave_overhang")), "a lifted bridge's first layer: lines (unset) = bridge lines from support to support; wave_overhang = wave beads (plan.technique.wave_overhang's spacing, overlap, line area, and speed) grown from every support at once over the whole span, each bead climbing from its support once lifted onto an arch, one support's side whole and then the other's, meeting where they are equally far from both (the peak of a symmetric arch); its layers above as the bridge's others", feeds = true)
        d("plan.technique.bridge.wave.sides", Type.Choice(listOf("one_then_other", "alternate", "one_support")), "the order of a waved first layer's sides (plan.technique.bridge.first_layer = wave_overhang): one_then_other (unset) = one support's side whole, then the other's; alternate = front by front, each front's beads on one side and then on the other, so neither side reaches out alone while the other is laid (every bead still climbs from its own support, and each side's edge beads come last); one_support = the fronts grow from one support only (the first in order of x then y) across the whole span, climbing to the peak and descending to the other support", feeds = true)
        d("plan.technique.bridge.wave.edges.order", Type.Choice(listOf("with_each_side", "last")), "when a waved first layer's edge pieces (its beads along the span's edges, each from its support up to the middle) are laid: with_each_side (unset) = after that side's beads across; last = after every bead across on both sides (with wave.sides = alternate they come last either way)", feeds = true)
        d("plan.technique.bridge.wave.edges.enabled", Type.Bool, "whether a waved first layer's edge pieces are laid at all: false = the beads across end at the span's edges; unset = laid", feeds = true)
        d("plan.technique.bridge.wave.line.direction", Type.Choice(listOf("mirrored", "matched", "zigzag")), "which edge a waved first layer's beads across the span start from: mirrored (unset) = as grown, each side's beads from one edge, the two sides from opposite edges; matched = every bead from the same edge of the span; zigzag = as matched, every other front's beads from the other edge, so the starts and ends spread over both edges", feeds = true)
        d("plan.technique.bridge.wave.crossing", Type.Choice(listOf("over", "beside", "away")), "how the head travels between a waved first layer's beads across the span: over (unset) = straight, above the bridge; beside = between the sides (wave.sides = alternate), out past the span's edge by wave.crossing_clearance, along it, and back in, so ooze on the way does not land on the bridge; away = after every bead across the span, out along the bead's own direction to wave.away.distance from the object (its outline seen from above, as a box), round the object at that distance the shorter way, and in to the next bead's start along that bead's direction, so the hot head is far from the beads between them; a wait (wave.wait, wave.wait_zones) is spent at the far point. The way round is not checked against the other objects on the plate: lay them out clear of it", feeds = true)
        q("plan.technique.bridge.wave.away.distance", Dimension.LENGTH, "how far from the object the head goes after each bead with wave.crossing = away; unset there is an error", feeds = true)
        d("plan.technique.bridge.wave.wait_zones", Type.Text, "waits after the beads across the span that vary along it, over wave.wait: zones of the span from the first support (in order of x then y) to the second, in percent, each with its wait, separated by commas (e.g. \"0-40%: 5 s, 40-60%: 10 s, 60-100%: 0 s\"); a bead takes the wait of the zone its middle lies in, spent before the next bead (at the far point with wave.crossing = away, else lifted over the next bead's start); the last bead waits none. Unset = wave.wait after every bead", feeds = true)
        d("plan.technique.bridge.wave.second_pass.zones", Type.Text, "a waved first layer's lines across the span laid a second time over it, after the whole layer, where zones of the span say (unset = none): zones from the first support (in order of x then y) to the second, in percent of the gap between them, each with how far above the first pass its lines are laid and their plastic as a share of the first pass's, separated by commas (e.g. \"25-40%: 0.1 mm 50%, 40-60%: 0 mm 100%, 60-75%: 0.1 mm 50%\"); a line belongs to the zone its middle lies in, and runs the other way to the line under it; the lines from the zones' outer ends inward, the two sides in turn, a line at the middle last; no edge pieces; at wave.second_pass.speed, each travel straight, lifted, and each line's start reached straight down from wave.second_pass.approach above it", feeds = true)
        q("plan.technique.bridge.wave.second_pass.speed", Dimension.SPEED, "the speed of a waved first layer's second pass (wave.second_pass.zones); unset there is an error", feeds = true)
        q("plan.technique.bridge.wave.second_pass.approach", Dimension.LENGTH, "how far above each line's start of a waved first layer's second pass the head comes straight down from (wave.second_pass.zones); unset there is an error", feeds = true)
        d("plan.technique.bridge.wave.x_layer.enabled", Type.Bool, "Xs over a waved first layer, laid after the whole layer (its edge pieces too): the span in sections of wave.x_length from each support, the one at the middle taking what is left of both sides; each X corner to corner between its section's first and last lines across the span, its ends wave.x_layer.inset in from the span's edges, wave.x_layer.raise above the layer at wave.x_layer.share of its lines' plastic; the two sides in turn from the supports to the middle, the middle X last; at wave.x_layer.speed. Needs a span between two supports and wave.spacing = even; unset = none", feeds = true)
        q("plan.technique.bridge.wave.x_layer.raise", Dimension.LENGTH, "how far above a waved first layer's top its Xs are laid (wave.x_layer.enabled; the X's line its own height under that); unset there is an error", feeds = true)
        q("plan.technique.bridge.wave.x_layer.share", Dimension.RATIO, "the Xs' plastic per mm as a share of the waved first layer's lines' (wave.x_layer.enabled); unset there is an error", feeds = true)
        q("plan.technique.bridge.wave.x_layer.inset", Dimension.LENGTH, "how far in from the span's edges each X's ends stop (wave.x_layer.enabled); unset there is an error", feeds = true)
        d("plan.technique.bridge.wave.x_layer.middle", Type.Choice(listOf("last", "first")), "when the X over the middle of a waved first layer's Xs is laid (wave.x_layer.enabled): last (unset) = after the sides' Xs; first = before them, the sides' then from the supports inward as before", feeds = true)
        q("plan.technique.bridge.wave.x_layer.speed", Dimension.SPEED, "the speed of the Xs over a waved first layer (wave.x_layer.enabled); unset there is an error", feeds = true)
        d("plan.technique.bridge.wave.span_layer.enabled", Type.Bool, "a full layer of lines along the span over a waved first layer, laid after it (and after its Xs, wave.x_layer): from support to support over the gap, at the solid fill's width and spacing (plan.layer.solid.line.width) across the gap's width, each the other way to the one before, wave.span_layer.raise above the waved layer's top, at wave.span_layer.speed and the solid fill's plastic. Needs a span between two supports and wave.spacing = even; unset = none", feeds = true)
        q("plan.technique.bridge.wave.span_layer.raise", Dimension.LENGTH, "how far above a waved first layer's top its layer of lines along the span is laid (wave.span_layer.enabled; the line's own height under that), at most two layers; unset there is an error", feeds = true)
        q("plan.technique.bridge.wave.span_layer.speed", Dimension.SPEED, "the speed of the layer of lines along the span over a waved first layer (wave.span_layer.enabled); unset there is an error", feeds = true)
        d("plan.technique.bridge.wave.spacing", Type.Choice(listOf("as_grown", "even")), "how a waved first layer's lines are spaced along the span: as_grown (unset) = each front wave_overhang.spacing on from the last, as measured from above, the two sides' last lines left with whatever space remains between them; even = the span from each support's first line to the other's divided into an even number of equal steps, as near wave_overhang.spacing as can be, measured along the bridge's surface (an arch's slopes as well as its top), so the lines lie equally far apart all along it and there is an odd number of them: each side's from its support, and one in the middle, laid after both sides' lines (with wave.line.direction = zigzag running the other way to both sides' last lines); each line straight across the span between its edges. Needs a span between two supports, wave.layers = 1, wave.start = whole and wave.front = straight; with wave.sides = one_support it does not apply", feeds = true)
        q("plan.technique.bridge.wave.crossing_clearance", Dimension.LENGTH, "how far past the span's edge the head goes on a crossing beside the bridge (wave.crossing = beside); plymeld's bundled value is a placeholder", feeds = true)
        q("plan.technique.bridge.wave.retract", Dimension.LENGTH, "the retraction before each travel between a waved first layer's beads, over the filament's retract.length (unset = the filament's)", feeds = true)
        d("plan.technique.bridge.wave.wipe", Type.Bool, "whether the head wipes along the bead it has just laid while retracting before each travel between a waved first layer's beads, over the filament's retract.wipe.enabled (unset = the filament's); the wipe's distance, speed, and share as the filament's", feeds = true)
        q("plan.technique.bridge.wave.wait", Dimension.TIME, "a wait before each bead of a waved first layer but its first, the head lifted over the bead's start (unset = none): more time for the bead before to cool", feeds = true)
        d("plan.technique.bridge.wave.layers", Type.Choice(listOf("1", "2")), "how many of a lifted bridge's first layers are laid as waves (plan.technique.bridge.first_layer = wave_overhang): 1 (unset) = the first; 2 = the first two together, the second's beads the first's fronts one layer up, laid one front behind the first's on each side in turn, so the second layer lands only where the first already reaches one front further out (needs wave.sides = alternate)", feeds = true)
        d("plan.technique.bridge.wave.second_layer", Type.Choice(listOf("full", "x")), "with two waved layers (wave.layers = 2), the second: full (unset) = every front, laid one front behind the first's; x = only an X over each section of the first layer (wave.x_length along the span), its two diagonals corner to corner from the section's first front's ends to its last front's, laid on each side as soon as the first layer covers its whole section; the sections run from each support, and the one at the middle, over where the sides meet, takes what is left of both sides (at least one section): the X's triangles stay open, and the rest of that layer over the gap is left to them but for the strip's edges, laid as on a full second layer (needs two supports, each front one line across)", feeds = true)
        q("plan.technique.bridge.wave.x_length", Dimension.LENGTH, "how far along the span each X of a waved second layer reaches (wave.second_layer = x), rounded to whole fronts (wave_overhang.spacing apart; at least two); unset with wave.second_layer = x is an error", feeds = true)
        d("plan.technique.bridge.wave.start", Type.Choice(listOf("whole", "edge_strips", "triangles", "opposite_triangles")), "the region a waved first layer lays first, as it lays a whole span (wave.sides, wave.line.direction, wave.crossing as set), before the rest of the span is filled with waves grown from the supports and from what was laid, front by front: whole (unset) = the whole span, nothing after; edge_strips = a strip wave.strip.width wide along each edge of the span, support to support; triangles = from each support a triangle as wide as the span there, narrowing to a point at the middle of one edge, the two meeting there; opposite_triangles = as triangles, the second support's point on the other edge. Every value but whole needs two supports and wave.layers = 1; with wave.sides = one_support it does not apply (the whole span is laid from the one support)", feeds = true)
        q("plan.technique.bridge.wave.strip.width", Dimension.LENGTH, "each edge strip's width with wave.start = edge_strips; unset there is an error", feeds = true)
        d("plan.technique.bridge.wave.front", Type.Choice(listOf("straight", "chevron")), "the shape of a waved first layer's fronts across the span: straight (unset) = as they grow from the supports; chevron = fronts shaped as chevrons, one part leading the rest by wave.chevron.lead (wave.chevron.leading: the edges, unset, or the middle), their lines along the fronts or across (wave.chevron.lines) (needs two supports, wave.layers = 1 and wave.start = whole; with wave.sides = one_support it does not apply)", feeds = true)
        q("plan.technique.bridge.wave.chevron.lead", Dimension.LENGTH, "how far a chevron front's leading part (wave.chevron.leading) leads the rest along the span (wave.front = chevron); unset = half the span, so with the edges leading the supports' triangles meet at the middle of the edges, and with the middle leading at the center point: the angle follows the bridge's width and span", feeds = true)
        d("plan.technique.bridge.wave.chevron.leading", Type.Choice(listOf("edges", "middle")), "which part of a chevron front leads (wave.front = chevron): edges (unset) = the edges, by wave.chevron.lead: from each support two triangles along the edges, the diamond between them last, closing on the center point; middle = the middle: from each support a triangle out of the support's middle, its tip leading, the two tips meeting at the center point first; then the regions along the edges, closing at the middle of each edge last", feeds = true)
        d("plan.technique.bridge.wave.chevron.lines", Type.Choice(listOf("along_fronts", "across")), "how a chevron's lines run (wave.front = chevron): along_fronts (unset) = along each front, diagonal, each front a line (or a ring where the fronts close in); across = every line parallel to the support's edge, as on a straight-front waved bridge, each row from a support laid in at most three pieces: first the part the leading region reaches (the middle piece with wave.chevron.leading = middle, else the two edge pieces), row by row from each support outward, then the rest of each row the same way, each piece from the leading region out", feeds = true)
        d("plan.technique.bridge.wave.after_supported", Type.Bool, "whether a waved first layer's lines come after what that layer lays on the supports (the strip over them): true = its first lines attach to that strip's edge at their own height as well as to the supports' top; unset = before it, the first lines resting on the supports' edge by wave_overhang.overlap alone (with wave.layers = 2 or wave.front = chevron they come after it either way)", feeds = true)
        val tk = "plan.technique.bridge.tack"
        q("$tk.layers", Dimension.COUNT, "how many of a lifted bridge's layers after its first are tacked to it (0: none): on each, before the layer, a short line where its first layer's bridge lines arch furthest from straight, laid at the height the layer would have over a first layer pulled straight between its ends, then rising back to the layer", feeds = true)
        q("$tk.length", Dimension.LENGTH, "how long each tack runs at its low height, along the bridge line", feeds = true)
        q("$tk.rise", Dimension.LENGTH, "how far along the line the tack rises back to the layer's height", feeds = true)
        q("$tk.spacing", Dimension.LENGTH, "how far apart the tacked bridge lines lie across the bridge: its two outermost first, then lines this far apart", feeds = true)
        // the surface techniques' and seam styles' own values, as flat layers' and the bridge's: read
        // where the job locks the technique (plan.faces.*, plan.seam); Z anti-aliasing and the back seam have none
        val wv = "plan.technique.wave_overhang"
        q("$wv.spacing", Dimension.LENGTH, "how far each front of a wave overhang lies from the last: the beads' center spacing, under a line width so each bead leans on the one before (the sources: about 0.35 mm for a 0.4 mm nozzle)", feeds = true)
        q("$wv.overlap", Dimension.LENGTH, "how far the first bead reaches back over the supported material it starts from", feeds = true)
        q("$wv.neck.min", Dimension.LENGTH, "a neck of the overhang narrower than this is not spanned: what lies beyond it is bridged", feeds = true)
        q("$wv.area.min", Dimension.AREA, "growth stops when a front adds less than this", feeds = true)
        q("$wv.line.area", Dimension.AREA, "the plastic per mm of a wave bead, as the bead's cross-section: laid in the air with nothing to squash it against, the layer height does not set it (the sources: 0.16 mm² for a 0.4 mm nozzle)", feeds = true)
        q("$wv.speed", Dimension.SPEED, "every wave bead at this speed (the sources: about 2 mm/s; over about 5 the bead no longer holds)", feeds = true)
        val ir = "plan.technique.ironing"
        q("$ir.spacing", Dimension.LENGTH, "gap between ironing lines", feeds = true)
        q("$ir.flow", Dimension.RATIO, "ironing flow, a share of a full line", feeds = true)
        q("$ir.inset", Dimension.LENGTH, "keep this far inside the edge", feeds = true)
        q("$ir.angle", Dimension.ANGLE, "ironing direction", feeds = true)
        q("$ir.speed", Dimension.SPEED, "ironing speed", feeds = true)
        val fz = "plan.technique.fuzzy_skin"
        d("$fz.where", Type.Choice(listOf("external", "all", "allwalls")), "which walls fuzzy skin takes: external = the outside outlines, all = with the holes, allwalls = every wall")
        q("$fz.thickness", Dimension.LENGTH, "how far points move, ±", feeds = true)
        q("$fz.point_distance", Dimension.LENGTH, "average spacing of the points", feeds = true)
        d("$fz.first_layer", Type.Bool, "fuzz the first layer too", feeds = true)
        val sc = "plan.technique.scarf_seam"
        d("$sc.enabled", Type.Bool, "scarf seams on the outer walls above the first layer that scarf_seam.where names (never on a fuzzed wall, nor a loop Z anti-aliasing lowers), each starting where plan.seam starts the loop; false = none. Unset = decided per object by the job's weights (seam_scar against the scarf's extra pass in time), with the placement where plan.seam is unset")
        d("$sc.where", Type.Choice(listOf("smooth", "all")), "which outer walls get a scarf seam when they are on: smooth = only loops whose seam does not land on a corner (with plan.seam = corners, the loops with no corner; with back, most loops), all = every outer wall. A scarf seam starts the loop thin and low, rising to the layer over scarf_seam.length, and ends re-printing that stretch thinning to nothing (the two wedges make one layer)")
        q("$sc.length", Dimension.LENGTH, "how long a scarf seam's ramp is (at most half the loop; a loop too short for a ramp of two line widths gets none)", feeds = true)
        q("$sc.start", Dimension.RATIO, "a scarf ramp's starting thickness, as a share of the layer (0 starts from nothing, the nozzle on the layer below)", feeds = true)
        q("$sc.speed", Dimension.SPEED, "a scarf seam's ramp and its closing pass no faster than this (the bead's thickness and the nozzle's height change along them). Bundled: half the visible lines' speed (plan.mechanism.motion.visible.speed), a placeholder until a print measures it", feeds = true)
        q("$sc.step", Dimension.LENGTH, "a scarf ramp's longest step: the ramp rises, and its bead thickens, in steps no longer than this (each step's plastic from the mean of its ends' thickness, exact only for short steps). Bundled: 1 mm", feeds = true)
        q("$sc.flow", Dimension.RATIO, "a scarf seam's ramp and its closing pass laid with this times their plastic (the two wedges, as laid, make one layer's bead). Bundled: 100%", feeds = true)
        q("plan.technique.corner_seam.corner_angle", Dimension.ANGLE, "the smallest turn of a wall loop (measured across two line widths each side) that counts as a corner for its seam (also for scarf seams' where = smooth)", feeds = true)
        val su = "plan.support"
        d("$su.enabled", Type.Bool, "the support switch, a lock: true = tree support under every face too steep to hold itself up; false = never (and a region's support = \"always\" is then refused); unset = the search decides per overhang (coordination phase 3), weighing each region's scars and plastic against the droop without it. Either way a region's support rule (plan.object.<key>.region.<key>.support) wins: always supported, or never", feeds = true)
        q("$su.interface.top_layers", Dimension.COUNT, "INHERITED: interface layers under the object", feeds = true)
        q("$su.interface.bottom_layers", Dimension.COUNT, "INHERITED: interface layers on what it stands on", feeds = true)
        q("$su.gap.top.value", Dimension.LENGTH, "the gap between support's top and the object it holds up, rounded to whole layers; unset = one layer, at least gap.top.min", feeds = true)
        q("$su.gap.top.min", Dimension.LENGTH, "the least gap between support's top and the object when gap.top.value is unset: one layer, or as many as reach this (0.2 mm: 3 layers at 0.08 mm)", feeds = true)
        q("$su.gap.bottom", Dimension.LENGTH, "the gap between support standing on the model and the model's top under it; the support's lowest interface.bottom_layers above the gap are bottom interface, as on the bed; unset = one layer (the layer on the model's top left out); 0 = laid onto it", feeds = true)
        q("$su.gap.side", Dimension.LENGTH, "the gap beside the object, between support and the object's walls; unset = plymeld's rule, one support line", feeds = true)
        q("$su.column.width", Dimension.LENGTH, "the narrowest column of support laid, so the narrowest overhang that gets support: an overhang is kept only where the overhang within this height above and below it, taken together, is at least this wide (a sloped face is a stack of thin strips, judged as a stack); what is narrower is a speck nothing could stand under and gets no support, a locked spot excepted; unset = plymeld's rule, a tree tip (two support lines)", feeds = true)
        q("$su.interface.spacing", Dimension.LENGTH, "the gap between interface lines (0 = touching); unset = plymeld's rule, lines at bead contact", feeds = true)
        q("$su.spacing", Dimension.LENGTH, "INHERITED: gap between support lines", feeds = true)
        d("$su.from_bed_only", Type.Bool, "INHERITED: only support standing on the bed", feeds = true)
        q("$su.line.width", Dimension.LENGTH, "INHERITED: support line width", feeds = true)
        q("$su.angle", Dimension.ANGLE, "INHERITED: support line direction", feeds = true)
        q("$su.slot", Dimension.COUNT, "the slot that prints support (0 = whichever is loaded)", feeds = true)
        q("$su.interface.slot", Dimension.COUNT, "the slot that prints support interface (0 = whichever is loaded)", feeds = true)
        q("$su.tree.branch_diameter", Dimension.LENGTH, "INHERITED: largest branch diameter", feeds = true)
        q("$su.tree.widening", Dimension.ANGLE, "INHERITED: how fast a branch widens going down", feeds = true)
        q("$su.tree.tip_spacing", Dimension.LENGTH, "INHERITED: spacing of the tips under an overhang", feeds = true)
        val pu = "plan.mechanism.purge"
        d("$pu.tower.enabled", Type.Bool, "INHERITED: purge into a tower", feeds = true)
        d("$pu.into", Type.Choice(listOf("tower", "infill", "infill_and_object")), "where the new filament's purge goes after a tool change: tower = all of it into the tower; infill = first into that filament's own sparse infill on the layer (printed right after the change, the dirtiest plastic hidden), the tower taking what is left; infill_and_object = its sparse infill, then its hidden solid infill, then the tower. Per object or part (plan.object.<key>.mechanism.purge.into) where a light part must not take a dark purge behind thin walls", feeds = true)
        d("$pu.tower.layout", Type.Choice(listOf("compact", "banded")), "INHERITED: visit the tower only on layers with a change, or every layer", feeds = true)
        q("$pu.tower.x", Dimension.LENGTH, "INHERITED: tower position, X of its lower-left corner; unset (with y) = beside the object (tower.gap)", feeds = true)
        q("$pu.tower.y", Dimension.LENGTH, "INHERITED: tower position, Y of its lower-left corner; unset (with x) = beside the object (tower.gap)", feeds = true)
        q("$pu.tower.gap", Dimension.LENGTH, "INHERITED: with tower.x and tower.y unset, the tower stands beside the object (its right, back, left or front, the first with room on the bed), this far from the object and its brim, its own brim included", feeds = true)
        q("$pu.tower.speed", Dimension.SPEED, "INHERITED: the purge tower's speed above its first layer, never faster than the machine prints (bundled: the visible lines' speed, plan.mechanism.motion.visible.speed)", feeds = true)
        q("$pu.tower.first_layer.speed", Dimension.SPEED, "INHERITED: the purge tower's first layer, its brim included, no faster than this (bundled: the object's first-layer speed)", feeds = true)
        d("$pu.tower.brim.enabled", Type.Bool, "INHERITED: a brim around the purge tower's first layer, for its hold on the bed (whatever the object's brim)", feeds = true)
        q("$pu.tower.brim.width", Dimension.LENGTH, "INHERITED: the purge tower's brim width", feeds = true)
        q("$pu.tower.brim.overlap", Dimension.LENGTH, "INHERITED: how far the purge tower brim's inner loop reaches into the tower's outline, so the two fuse (a brim only touching the tower held the bed while the tower peeled off it); 0 = they only touch", feeds = true)
        q("$pu.tower.side_share.min", Dimension.RATIO, "INHERITED: each side of the purge tower at least this share of its height (a tall, thin tower sways): a tower its purge would leave narrower is widened and deepened; 0 = no minimum", feeds = true)
        // ---- method: plymeld's own algorithms
        q("method.search.time.max", Dimension.TIME, "how long the search for a plan may run", feeds = true)
        // the visibility map: placeholders until measured
        q("method.quality.visibility.under", Dimension.RATIO, "how much a face seen only from below counts toward quality's measures (a horizontal underside this share; a face leaning a little below level nearly in full): the visibility map every quality measure is weighted by; the bundled value measured on a seam plate (plymeld calibrate seams)", feeds = true)
        q("method.quality.visibility.bed", Dimension.RATIO, "how much a face standing on the bed counts toward quality's measures: the visibility map every quality measure is weighted by; a placeholder until measured", feeds = true)
        q("method.quality.visibility.away", Dimension.RATIO, "how much a face turned squarely away from where the print is looked at from (plan.view_from) counts toward quality's measures (part way: between this and 1), not a seam's (method.quality.visibility.quadrant.*); a placeholder until measured", feeds = true)
        for (side in listOf("front", "left", "right", "back"))
            q("method.quality.visibility.quadrant.$side", Dimension.RATIO, "the least-visible seam: how much the sampled viewing directions from the $side quadrant count (the 90 degrees around the print's $side as seen from plan.view_from; a direction on a quadrant's edge counts the mean of the two); the most-seen open wall reads 1; every direction alike with plan.view_from all_around or above", feeds = true)
        q("method.quality.stair_step.depth_power", Dimension.RATIO, "the stair-step measure (surface_step): each face's terrace edges per mm (the sine of its angle to its layer / the layer's height) x each edge's depth to this power (2: an edge half as deep counts a quarter, so thinner layers score better and a face that follows its layer shows few edges); a placeholder until measured", feeds = true)
        d("plan.view_from", Type.Choice(listOf("all_around", "front", "back", "left", "right", "above")), "where the finished print is looked at from, for quality's measures (the visibility map); bundled: front. all_around = no face counts as turned away (a model with no front of its own); front, back, left, right, above = the model's own sides as given (front is its small-y side, toward the printer's door when it prints as given), turned with whatever pose the plan gives it. A face turned away from the viewer counts method.quality.visibility.away by how squarely it faces away; a seam placed least_visible goes where its viewing directions, weighed by the quadrant they look from (method.quality.visibility.quadrant.*), see it least. Per object: plan.object.<key>.view_from", feeds = true)
        q("method.quality.visibility.sample.azimuths", Dimension.COUNT, "how many viewing directions around the print a seam's point is sampled from, for what hides it nearby (the seam placement and the seam measure); a placeholder", feeds = true)
        q("method.quality.visibility.sample.elevations", Dimension.COUNT, "how many heights each viewing direction is sampled at, evenly from level up to method.quality.visibility.sample.elevation.max (1 = level only); a placeholder", feeds = true)
        q("method.quality.visibility.sample.elevation.max", Dimension.ANGLE, "the highest viewing direction sampled above level for what hides a seam nearby; a placeholder", feeds = true)
        q("method.quality.visibility.sample.reach", Dimension.LENGTH, "how far from a seam's point a viewing ray is followed for material that hides it (0 = nothing hides it); a placeholder", feeds = true)
        q("method.quality.visibility.sample.cell", Dimension.LENGTH, "the grid each layer's outline is read on when following a viewing ray; a placeholder", feeds = true)
        q("method.quality.visibility.show_through", Dimension.LENGTH, "how deep behind a visible surface a seam still shows through it (a seam whose viewing ray meets only material it leaves within this distance counts 1 - that distance / this); 0 = any material hides a seam; a placeholder", feeds = true)
        q("method.quality.seam.scarf_share", Dimension.RATIO, "the seam measure (seam_scar): a scarf seam's blemish as a share of a point seam's (one line width squared), whatever the scarf's length; the bundled value measured on a seam plate (plymeld calibrate seams)", feeds = true)
        q("method.quality.seam.corner_share", Dimension.RATIO, "the seam measure (seam_scar): a point seam in a corner (a turn of at least plan.technique.corner_seam.corner_angle) as a share of one on a smooth wall; the bundled value measured on a seam plate (plymeld calibrate seams)", feeds = true)
        // the bond model: placeholders until measured (the helix-layer coupon)
        q("method.strength.bond.fan_loss", Dimension.RATIO, "the bond between layers lost to a full part fan, against none (the bond factor's fan term, 1 - fan_loss x fan); a placeholder until measured", feeds = true)
        q("method.strength.bond.temperature_gain", Dimension.RATIO, "the bond between layers gained per 10 C of nozzle above the filament's print temperature (lost below it); a placeholder until measured", feeds = true)
        q("method.strength.bond.cooling_loss", Dimension.RATIO, "the bond between layers lost when the layer below has cooled for bond.cooling_time or longer (the bond factor's cooling term); a placeholder until measured", feeds = true)
        q("method.strength.bond.cooling_time", Dimension.TIME, "the layer time after which the layer below counts as fully cooled for the bond; a placeholder until measured", feeds = true)
        q("method.strength.bond.enough_area", Dimension.AREA, "a weakest bond between layers this large (after the bond factor) is strong enough: bond.strength gains nothing above it; a placeholder until measured", feeds = true)
        q("method.search.pose.shortlist_margin", Dimension.RATIO, "the first look at every pose (one plan each: flat layers at the baseline's height, no support chosen) shortlists the poses searched in full: the as-given pose and those scoring within this of the best there, per unit of weight in effect (as keep_margin); a pose left out is not searched, so a pose that looks poor under one plan but would win with its own is missed for the time saved; unset = every pose searched", feeds = true)
        q("method.search.pose.keep_margin", Dimension.RATIO, "the model's own orientation has standing: another pose must beat the as-given pose's best candidate by more than this score per unit of weight in effect (the gap divided by the sum of the goal weights the decision measures: the same margin at any total) to turn the model; 0 = the best score wins outright", feeds = true)
        q("method.search.layer.heights", Dimension.COUNT, "how many layer heights of flat layers (one height throughout) the search tries per pose across the tool's range, coarse to fine: the range's ends and the baseline (else the middle) in every pose, then, round by round, the midpoint of the interval next to the leading candidate that could still win", feeds = true)
        q("method.search.settle_rounds", Dimension.COUNT, "how many refinement rounds of layer height in a row may leave the winner as it was before the search stops there; 0 = no such stop", feeds = true)
        q("method.search.baseline.layer.height_share", Dimension.RATIO, "the baseline the search measures every difference against: flat layers of one height, this share of the nozzle diameter of the tool that prints the object (default 50%)", feeds = true)
        for (m in MEASURES) d("method.search.zero_scale.$m", Type.Text, "scale for scoring the measure $m when its baseline value is 0, in its own unit, e.g. \"500 mm2\"")
        d("method.search.log.enabled", Type.Bool, "whether every technique decision appends one line (date, model, stop reason, time, rounds) to the search log, so how often the search short-circuits is counted over real use", feeds = true)
        d("method.search.log.file", Type.Text, "the search log's file; empty = search.log in plymeld's state folder; a leading ~/ is your home directory; made with its directories when missing", feeds = true)
        q("method.slicing.tolerance", Dimension.LENGTH, "outline simplification", feeds = true)
        q("method.slicing.close_gaps", Dimension.LENGTH, "gaps narrower than twice this are closed", feeds = true)
    }

    /**
     * Settings no one path feeds, by id: made from several values (ConfigSetup.derived), with the paths they are made from
     * (each is named where its key would be, in the group of those paths or the keys it belongs with).
     */
    val made: Map<String, List<String>> = linkedMapOf(
        "hardware.machine.build.left" to listOf("hardware.machine.<key>.build.x", "hardware.machine.<key>.build.origin"),
        "hardware.machine.build.right" to listOf("hardware.machine.<key>.build.x", "hardware.machine.<key>.build.origin"),
        "hardware.machine.build.front" to listOf("hardware.machine.<key>.build.y", "hardware.machine.<key>.build.origin"),
        "hardware.machine.build.back" to listOf("hardware.machine.<key>.build.y", "hardware.machine.<key>.build.origin"),
        "hardware.filament.bed.temperature.print" to listOf("hardware.filament.<key>.bed.<key>.temperature.print", "hardware.plate.<key>.bed.<key>.temperature.print"),
        "hardware.filament.bed.temperature.first_layer" to listOf("hardware.filament.<key>.bed.<key>.temperature.first_layer", "hardware.plate.<key>.bed.<key>.temperature.first_layer",
            "hardware.filament.<key>.bed.<key>.temperature.print", "hardware.plate.<key>.bed.<key>.temperature.print"),
        "hardware.filament.temperature.idle_drop" to listOf("hardware.filament.<key>.temperature.print", "hardware.filament.<key>.temperature.idle"),
        "job.priorities" to GOALS.map { "job.priorities.$it" } + MEASURES.map { "job.priorities.measure.$it" },
        // the locks and the techniques' choices beside them: the pipeline's setting holds the choice, "none" when
        // the technique is not locked
        "plan.technique.fuzzy_skin.where" to listOf("plan.faces.side.technique", "plan.technique.fuzzy_skin.where"),
        "plan.technique.ironing.type" to listOf("plan.faces.flat_top.technique"),
        "plan.faces.sloped_top.z_antialiasing" to listOf("plan.faces.sloped_top.technique"),
        "plan.faces.under.wave_overhang" to listOf("plan.faces.under.technique"),
        "plan.technique.scarf_seam.where" to listOf("plan.technique.scarf_seam.enabled", "plan.technique.scarf_seam.where"),
        "method.search.zero_scale" to MEASURES.map { "method.search.zero_scale.$it" },
        "plan.layer.walls.count" to listOf("plan.layer.walls.thickness", "plan.layer.walls.outer.line.width", "plan.layer.walls.inner.line.width"),
        "plan.layer.walls.line.width.min" to listOf("hardware.tool.<key>.line.width.min", "hardware.tool.<key>.bore"),
        "plan.layer.walls.feature.min" to listOf("hardware.tool.<key>.line.width.min", "hardware.tool.<key>.bore"),
        "plan.layer.walls.length_factor.min" to listOf("hardware.tool.<key>.line.width.min", "hardware.tool.<key>.bore"),
        "plan.layer.walls.transition_length" to listOf("hardware.tool.<key>.line.width.min", "hardware.tool.<key>.bore"),
        // switches and the choices beside them: the pipeline's setting holds the choice, "none" when off
        "plan.mechanism.adhesion.brim.where" to listOf("plan.mechanism.adhesion.brim.enabled", "plan.mechanism.adhesion.brim.where"),
        "plan.mechanism.adhesion.prime.line" to listOf("plan.mechanism.adhesion.prime.enabled", "plan.mechanism.adhesion.prime.line",
            "plan.mechanism.adhesion.brim.enabled"),
        "plan.mechanism.motion.first_layer.speed" to listOf("plan.mechanism.motion.first_layer.speed", "hardware.filament.<key>.first_layer.window.<key>.<key>.speed.max",
            "hardware.filament.<key>.motion.first_layer.speed", "hardware.machine.<key>.motion.first_layer.speed"))

    /**
     * Settings no path sets at all, by id, and why: read only from another slicer's settings (investigation).
     * Each is named where its key would be, in the group of the keys it belongs with.
     */
    val unkeyed: Map<String, String> = linkedMapOf(
        "plan.layer.infill.anchor_length.max" to "another slicer's only",
        "plan.layer.infill.area.min" to "another slicer's only",
        "plan.support.tree.branch_angle" to "another slicer's only",
        "plan.support.tree.tip_diameter" to "another slicer's only",
        "plan.mechanism.purge.tower.width" to "another slicer's only",
        "plan.technique.fuzzy_skin.mode" to "another slicer's only",
        "plan.technique.fuzzy_skin.noise" to "another slicer's only")

    /**
     * The config paths that set setting [id], for messages ("hardware.machine.<key>.model"), several joined with " / ";
     * null when none does ([unkeyed]).
     */
    fun keysOf(id: String): String? =
        (all.filter { it.feeds == id }.map { it.path }.ifEmpty { null } ?: made[id])?.joinToString(" / ")

    /**
     * Where [ids] (the catalog's) break the naming convention, one line each; empty when none does. An id a
     * path feeds is that path without its `<key>` segments, by construction ([idOf]); every other id must be listed in
     * [made] (with declared paths) or [unkeyed], and lie in a group of declared paths (its parent is a declared path's
     * parent), so it reads as a key would; it may equal a declared path only one it is made from. The lists' own entries
     * must be ids, and none both fed and listed. [made] and [unkeyed] default to the schema's own (a test passes others).
     */
    fun conventionProblems(ids: Collection<String>, made: Map<String, List<String>> = this.made,
                           unkeyed: Map<String, String> = this.unkeyed): List<String> {
        val out = ArrayList<String>()
        val fed = all.mapNotNull { it.feeds }.toSet()
        val declared = all.associateBy { idOf(it.path) }
        val groups = declared.keys.map { it.substringBeforeLast('.', "") }.toSet()
        for (id in ids) {
            val from = made[id]
            when {
                id in fed -> if (from != null || id in unkeyed) out += "$id: a path feeds it, yet it is listed in Schema.made / unkeyed"
                from == null && id !in unkeyed ->
                    out += "$id: no config path feeds it (its id would be the path without <key>), and it is not in Schema.made or Schema.unkeyed"
                else -> {
                    if (from != null && id in unkeyed) out += "$id: in both Schema.made and Schema.unkeyed"
                    from?.filter { find(it.split('.')) == null }?.forEach { out += "$id: made from '$it', which is not a declared path" }
                    // a min / max leaf bounds a quantity, which may be a thing and its property (walls.line.width.min)
                    val segs = id.split('.'); val bounded = segs.dropLastWhile { it == "min" || it == "max" }
                    val inGroup = bounded.joinToString(".").substringBeforeLast('.', "") in groups ||
                        (bounded.size < segs.size && bounded.dropLast(2).joinToString(".") in groups)
                    if (!inGroup) out += "$id: not in a group of config paths (${id.substringBeforeLast('.', "(none)")} holds no declared path)"
                    declared[id]?.let { d -> if (from == null || d.path !in from) out += "$id: is the config path ${d.path}, which does not set it" }
                }
            }
        }
        for (id in made.keys + unkeyed.keys) if (id !in ids) out += "$id: listed in Schema.made / unkeyed, but no such setting"
        return out
    }

    /**
     * One word for one thing (rule 2): a word a key may not use, with the word it uses instead. Checked against every
     * word of every key ([namingProblems]).
     */
    val RETIRED_WORDS = linkedMapOf("velocity" to "speed", "accel" to "acceleration", "bead" to "line (a line's width is line.width)",
        "structure" to "pattern", "temp" to "temperature", "limit" to "a .min / .max leaf")

    /** Words that name a length: a key whose last word is one of them holds a length (rule 4). */
    private val LENGTH_WORDS = setOf("width", "height", "length", "distance", "spacing", "gap", "thickness", "radius", "diameter", "inset")

    /**
     * Where the config keys [decls] (and the ids no one key sets, [ids]) break the naming rules that can be checked mechanically
     *, one line each; empty when none does. The rules, by number:
     *  - 2: lower-case words joined by `_`, no retired word ([RETIRED_WORDS]);
     *  - 4: a speed's key ends with `speed`, an acceleration's with `acceleration`, a temperature's with `temperature` or is a
     *    stage of a `temperature` table; a length word ends only a length (each read on the last segment that is not a
     *    `min` / `max` leaf: `motion.speed.max` is a speed);
     *  - 5: `min` / `max` only as a leaf, a whole segment after what it bounds (`temperature.max`, never `max_temperature`,
     *    `time_max` or `max.temperature`);
     *  - 6: a thing and its property are levels: a line's width is `line.width`, a layer's height `layer.height`, a move's
     *    speed and acceleration `<move>.speed` / `<move>.acceleration` (no `line_width`, `layer_height`, `print_speed`);
     *    a name stays whole (`wave_overhang`, `first_layer`);
     *  - 7: a boolean has no `<name>_*` siblings and no `<name>.*` table (that is `<name>.enabled`); a job or plan choice has
     *    no `"none"` (off is `enabled = false`);
     *  - 8: an axis is a `.x` / `.y` / `.z` member (no `_x`);
     *  - 9: `filament` holds a filament's key, `slot` a slot's number;
     *  - 10: one setting, one tail: no two keys spell the same words in another shape (`speed.first_layer` beside
     *    `motion.first_layer.speed`).
     * [decls] and [ids] default to the schema's own (a test passes others).
     */
    fun namingProblems(decls: List<Decl> = this.decls, ids: Collection<String> = made.keys + unkeyed.keys): List<String> {
        val out = ArrayList<String>()
        val segment = Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*")
        fun words(s: String) = s.split('_')
        val paths = decls.map { it.path } + ids
        for (p in paths) {
            val segs = p.split('.').filter { it != "<key>" }
            for (s in segs) {
                if (!segment.matches(s)) out += "$p: '$s' is not lower-case words joined by _ (rule 2)"
                for (w in words(s)) RETIRED_WORDS[w]?.let { out += "$p: '$w' is retired: write $it (rule 2)" }
                if (words(s).size > 1 && words(s).any { it == "min" || it == "max" }) out += "$p: '$s': min / max are a leaf, a segment of their own after what they bound (temperature.max) (rule 5)"
                if (s.endsWith("_width")) out += "$p: '$s': a line's width is line.width; a thing's own width is a 'width' segment (rule 6)"
                if (s.endsWith("_height") && words(s).dropLast(1).last() == "layer") out += "$p: '$s': a layer's height is layer.height (rule 6)"
                if (s.endsWith("_speed") || s.endsWith("_acceleration")) out += "$p: '$s': a thing's speed or acceleration is <thing>.speed / <thing>.acceleration (rule 6)"
                if (Regex(".*_[xyz]").matches(s)) out += "$p: '$s': an axis is a .x / .y / .z member (rule 8)"
            }
            segs.dropLast(1).filter { it == "min" || it == "max" }.forEach { out += "$p: '$it' is not the last segment: min / max are a leaf (rule 5)" }
        }
        for (d in decls) {
            val segs = d.segments
            // rule 4 reads what a min / max leaf bounds: motion.speed.max is a speed
            val bounded = segs.dropLastWhile { it == "min" || it == "max" }
            val last = bounded.last()
            val parent = bounded.getOrNull(bounded.size - 2)
            val t = d.type
            if (t is Type.Quantity) {
                val ends = when (t.dim) { Dimension.SPEED -> "speed"; Dimension.ACCEL -> "acceleration"; Dimension.TEMPERATURE -> "temperature"; else -> null }
                if (ends != null && words(last).last() != ends && !(t.dim == Dimension.TEMPERATURE && parent == "temperature"))
                    out += "${d.path}: a ${t.dim.name.lowercase()} ends with '$ends' (rule 4)"
                // a weight on a measure (job.priorities.measure.<measure>) is named after the measure, whatever that measures
                val namesAMeasure = t.dim == Dimension.WEIGHT && segs.size == 4 && segs[0] == "job" && segs[1] == "priorities" && segs[2] == "measure"
                if (words(last).last() in LENGTH_WORDS && t.dim != Dimension.LENGTH && !namesAMeasure) out += "${d.path}: '$last' names a length, but it holds ${t.label} (rule 4)"
            }
            if (t is Type.Bool && last != "enabled") {
                val prefix = d.path + "_"; val table = d.path + "."
                if (decls.any { it.path.startsWith(prefix) || it.path.startsWith(table) })
                    out += "${d.path}: a switch with values of its own is ${d.path}.enabled beside them (rule 7)"
            }
            if (t is Type.Choice && (segs[0] == "job" || segs[0] == "plan") && "none" in t.options)
                out += "${d.path}: a choice has no \"none\": off is <feature>.enabled = false (rule 7)"
            if (last == "filament" && !(t is Type.Ref && t.kind == "filament")) out += "${d.path}: 'filament' holds a filament's key; a slot's number is 'slot' (rule 9)"
            if (last == "slot" && !(t is Type.Quantity && t.dim == Dimension.COUNT)) out += "${d.path}: 'slot' holds a slot's number (rule 9)"
        }
        // rule 10: the same words in two shapes (a key's last one or two segments against another's: speed.first_layer
        // beside first_layer.speed); the same words in the same order, split or joined (line_width, line.width), are rule 6's
        val tails = decls.map { d -> d.segments.filter { it != "<key>" } }.filter { it.size >= 2 }
        fun bag(segs: List<String>) = segs.flatMap { words(it) }.sorted()
        for (a in tails) for (b in tails) for (i in 1..2) for (j in 1..2) {
            val x = a.takeLast(i); val y = b.takeLast(j)
            if (bag(x) == bag(y) && x.joinToString("_") != y.joinToString("_"))
                out += "${a.joinToString(".")} and ${b.joinToString(".")}: the same words in two shapes ('${x.joinToString(".")}', '${y.joinToString(".")}'); one setting has one tail (rule 10)"
        }
        return out.distinct()
    }

    /**
     * Why a value of [path] is refused although its key is known, or null: `derive` as a band's heights (an
     * object's bands lock its plan, so nothing is weighed per band).
     */
    fun refusedValue(path: List<String>, value: Any?): String? {
        if (path.size == 6 && path[0] == "plan" && path[1] == "object" && path[3] == "band" && path[5] == "heights" && value == "derive")
            return "a band's heights are \"one\" or \"follow\", not \"derive\": an object's bands lock its plan, so nothing is weighed per band; " +
                "to have the search weigh both, leave the object's bands out (plan.technique.flat.heights = \"derive\" or unset)"
        return null
    }

    /** The declaration [path] matches (scopes removed), or null. Wildcards match any key. */
    fun find(path: List<String>): Decl? = match(path) ?: match(unscoped(path))

    private fun match(p: List<String>): Decl? = all.firstOrNull { d -> d.segments.size == p.size && d.segments.indices.all { d.segments[it] == "<key>" || d.segments[it] == p[it] } }

    /** [path] without an object / part / region scope: `job.object.K.x` -> `job.x`, `plan.object.K.part.P.x` -> `plan.x`. */
    fun unscoped(path: List<String>): List<String> {
        if (path.size > 3 && (path[0] == "job" || path[0] == "plan") && path[1] == "object") {
            val rest = if (path.size > 5 && (path[3] == "part" || path[0] == "plan" && path[3] == "region")) path.drop(5) else path.drop(3)
            return listOf(path[0]) + rest
        }
        return path
    }
}
