package plymeld.config

import plymeld.model.Catalog
import plymeld.model.Entity
import plymeld.model.ImportReport
import plymeld.model.ObjectSetup
import plymeld.model.Setup
import plymeld.model.Sourced
import plymeld.model.Spool
import plymeld.model.Values

/**
 * Maps a QIDIStudio / Orca / Flash Studio settings file ONTO plymeld's concept model
 *. Orca's arrays are split into entity instances:
 * per-extruder slots -> TOOLs, per-filament slots -> MATERIAL + SPOOL (Orca's "filament" is both),
 * and values that Orca stores per extruder but lets a filament override -> MATERIAL x TOOL.
 * `filament_map` (1-based) says which tool each filament is in: Q2 + Box = 1,1,1,1; C5 = 1,2,3,4.
 */
object OrcaImport {
    private class Row(val id: String, val keys: List<String>, val read: (Ctx) -> Any)

    /** Where a row is being read: filament slot [f] in tool [t] (or 0/0 for machine and intent rows). */
    private class Ctx(val c: OrcaConfig, val f: Int, val t: Int) {
        /** Keys whose value ended up in the setting (provenance). */
        val used = LinkedHashSet<String>()
        /** Keys looked at, including ones that deferred to another ("nil") — all count as mapped. */
        val consulted = LinkedHashSet<String>()
        fun slot(key: String, i: Int): String = peekSlot(key, i).also { used.add(key) }
        fun peekSlot(key: String, i: Int): String {
            val v = c.list(key) ?: error("setting '$key' missing from the imported config")
            consulted.add(key)
            return if (v.size == 1) v[0] else v.getOrNull(i) ?: error("setting '$key' has no slot ${i + 1}")
        }
        fun has(key: String) = c.list(key) != null
    }

    private fun num(v: String) = v.toDouble()
    private fun pct(v: String) = v.removeSuffix("%").toDouble() / 100
    private fun bool(v: String) = v == "1"

    // --- row builders, by which slot of an Orca array the value comes from
    private val rows = ArrayList<Row>()

    /** The other slicer's setting names each plymeld path is read from (for the finder: a user types the name they know). */
    fun namesOf(): Map<String, List<String>> = rows.groupBy({ it.id }, { it.keys }).mapValues { (_, ks) -> ks.flatten().distinct() }
    private fun machine(id: String, key: String, conv: (String) -> Any) { rows += Row(id, listOf(key)) { conv(it.slot(key, 0)) } }
    private fun tool(id: String, key: String, conv: (String) -> Any) { rows += Row(id, listOf(key)) { conv(it.slot(key, it.t)) } }
    private fun filament(id: String, key: String, conv: (String) -> Any) { rows += Row(id, listOf(key)) { conv(it.slot(key, it.f)) } }
    private fun intent(id: String, key: String, conv: (String) -> Any) = machine(id, key, conv)
    /** Orca: a per-extruder value that the filament may override with `filament_<key>` ("nil" = keep the extruder's). */
    private fun toolUnlessFilament(id: String, key: String, conv: (String) -> Any) {
        rows += Row(id, listOf(key, "filament_$key")) { x ->
            val o = if (x.has("filament_$key")) x.peekSlot("filament_$key", x.f) else "nil"
            conv(if (o == "nil") x.slot(key, x.t) else o.also { x.used.add("filament_$key") })
        }
    }
    private fun custom(id: String, keys: List<String>, read: (Ctx) -> Any) { rows += Row(id, keys, read) }
    /** "N%" means a share of the default acceleration (Orca's convention). */
    private fun accel(id: String, key: String) = custom(id, listOf(key, "default_acceleration")) { x ->
        val v = x.slot(key, 0); if (v.endsWith("%")) num(x.slot("default_acceleration", 0)) * pct(v) else num(v)
    }

    init {
        machine("hardware.machine.model", "printer_model") { modelFor(it) }
        machine("hardware.machine.firmware.flavor", "gcode_flavor") { it }
        machine("hardware.machine.build.z", "printable_height", ::num)
        // printable_area: the bed outline as "XxY" corners; plymeld keeps its bounding box
        for ((id, axis, pick) in listOf(Triple("hardware.machine.build.left", 0, "min"), Triple("hardware.machine.build.front", 1, "min"), Triple("hardware.machine.build.right", 0, "max"), Triple("hardware.machine.build.back", 1, "max")))
            custom(id, listOf("printable_area")) { x ->
                val v = (x.c.list("printable_area") ?: error("setting 'printable_area' missing from the imported config")).also { x.used.add("printable_area"); x.consulted.add("printable_area") }
                    .map { it.split('x')[axis].toDouble() }
                if (pick == "min") v.min() else v.max()
            }
        machine("hardware.machine.gcode.start", "machine_start_gcode") { it }
        machine("hardware.machine.gcode.end", "machine_end_gcode") { it }
        custom("hardware.machine.gcode.layer_change", listOf("layer_change_gcode")) { x -> if (x.has("layer_change_gcode")) x.slot("layer_change_gcode", 0) else "" }
        custom("hardware.machine.gcode.pause", listOf("machine_pause_gcode")) { x -> if (x.has("machine_pause_gcode")) x.slot("machine_pause_gcode", 0) else "" }
        machine("hardware.machine.firmware.exclude_object", "exclude_object", ::bool)
        machine("hardware.machine.firmware.arcs", "enable_arc_fitting", ::bool)
        machine("hardware.plate.first_layer.squish", "elefant_foot_compensation", ::num)
        machine("hardware.machine.motion.travel.speed", "travel_speed", ::num)
        machine("hardware.machine.motion.print.speed", "machine_max_speed_x", ::num)
        machine("hardware.machine.motion.print.acceleration", "default_acceleration", ::num)
        accel("hardware.machine.motion.travel.acceleration", "travel_acceleration")

        tool("hardware.tool.bore", "nozzle_diameter", ::num)
        tool("hardware.filament.retract.z_hop", "z_hop", ::num)
        tool("hardware.tool.layer.height.max", "max_layer_height", ::num)
        tool("hardware.tool.layer.height.min", "min_layer_height", ::num)

        filament("hardware.filament.diameter", "filament_diameter", ::num)
        filament("hardware.filament.bed.temperature.print", "hot_plate_temp", ::num)
        filament("hardware.filament.bed.temperature.first_layer", "hot_plate_temp_initial_layer", ::num)
        filament("hardware.filament.cooling.slowdown.enabled", "slow_down_for_layer_cooling", ::bool)
        filament("hardware.filament.cooling.layer.time.min", "slow_down_layer_time", ::num)
        filament("hardware.filament.cooling.speed.min", "slow_down_min_speed", ::num)
        filament("hardware.filament.cooling.fan_off_layers", "close_fan_the_first_x_layers", ::num)
        filament("hardware.filament.cooling.fan", "fan_min_speed") { num(it) / 100 }   // the profile's percent, plymeld's share
        // the overhang fan as the normal fan (no boost), as investigation slices have printed so far;
        // Orca's own overhang_fan_speed is not mapped yet
        filament("hardware.filament.overhang.fan", "fan_min_speed") { num(it) / 100 }
        filament("hardware.filament.color", "filament_colour") { it }

        filament("hardware.filament.temperature.print", "nozzle_temperature", ::num)
        filament("hardware.filament.temperature.first_layer", "nozzle_temperature_initial_layer", ::num)
        filament("hardware.filament.flow", "filament_flow_ratio", ::num)
        filament("hardware.filament.melt.flow.max", "filament_max_volumetric_speed", ::num)
        toolUnlessFilament("hardware.filament.retract.length", "retraction_length", ::num)
        toolUnlessFilament("hardware.filament.retract.speed", "retraction_speed", ::num)
        toolUnlessFilament("hardware.filament.retract.return.speed", "deretraction_speed", ::num)
        toolUnlessFilament("hardware.filament.retract.travel.min", "retraction_minimum_travel", ::num)
        toolUnlessFilament("hardware.filament.retract.wipe.enabled", "wipe", ::bool)
        toolUnlessFilament("hardware.filament.retract.wipe.distance", "wipe_distance", ::num)
        toolUnlessFilament("hardware.filament.retract.wipe.retract_before", "retract_before_wipe", ::pct)
        custom("hardware.filament.retract.wipe.speed", listOf("wipe_speed", "travel_speed")) { x ->
            val v = x.slot("wipe_speed", 0); if (v.endsWith("%")) num(x.slot("travel_speed", 0)) * pct(v) else num(v)
        }

        intent("plan.technique.flat.layer.height", "layer_height", ::num)
        intent("plan.mechanism.adhesion.first_layer.height", "initial_layer_print_height", ::num)
        intent("method.slicing.tolerance", "resolution", ::num)
        intent("method.slicing.close_gaps", "slice_closing_radius", ::num)
        intent("plan.layer.walls.count", "wall_loops", ::num)
        // arachne thresholds: "N%" = of the nozzle diameter (first tool), else mm; absent -> unset
        for ((id, key) in listOf("plan.layer.walls.line.width.min" to "min_bead_width", "plan.layer.walls.feature.min" to "min_feature_size", "plan.layer.walls.transition_length" to "wall_transition_length"))
            custom(id, listOf(key, "nozzle_diameter")) { x ->
                if (!x.has(key)) Unit else x.slot(key, 0).let { v -> if (v.endsWith("%")) pct(v) * num(x.slot("nozzle_diameter", 0)) else num(v) }
            }
        intent("plan.layer.walls.outer.line.width", "outer_wall_line_width", ::num)
        intent("plan.layer.walls.inner.line.width", "inner_wall_line_width", ::num)
        intent("plan.layer.walls.order", "wall_sequence") { when (it) { "inner wall/outer wall" -> "inner_first"; "inner-outer-inner wall" -> "inner_outer_inner"; else -> "outer_first" } }
        intent("plan.seam", "seam_position") { if (it == "back") "back" else "corners" }   // aligned / nearest / random: kept aligned, at corners
        intent("plan.mechanism.adhesion.first_layer.line.width", "initial_layer_line_width", ::num)
        // plymeld's shells are a thickness; the other slicer's layer count, at its layer height, is
        // the thickness it means (the larger of the two, as it prints them)
        for (side in listOf("top", "bottom")) custom("plan.layer.$side.thickness", listOf("${side}_shell_layers", "${side}_shell_thickness", "layer_height")) { x ->
            val byCount = num(x.slot("${side}_shell_layers", 0)) * num(x.slot("layer_height", 0))
            maxOf(byCount, if (x.has("${side}_shell_thickness")) num(x.slot("${side}_shell_thickness", 0)) else 0.0)
        }
        intent("plan.layer.infill.density", "sparse_infill_density", ::pct)
        intent("plan.layer.infill.pattern", "sparse_infill_pattern") { it }
        intent("plan.layer.infill.line.width", "sparse_infill_line_width", ::num)
        intent("plan.layer.solid.line.width", "internal_solid_infill_line_width", ::num)
        intent("plan.layer.top.line.width", "top_surface_line_width", ::num)
        intent("plan.layer.infill.angle", "infill_direction", ::num)
        intent("plan.layer.infill.wall_overlap", "infill_wall_overlap", ::pct)
        intent("plan.layer.infill.anchor_length.max", "sparse_infill_anchor_max", ::num)
        // speeds: plymeld has a visible cap, a first-layer speed, and a bridge speed; hidden lines print at
        // the machine's fastest move. The other slicer's outer wall speed / acceleration is its visible cap;
        // its other per-feature speeds have no plymeld setting (reported as not mapped)
        intent("plan.mechanism.motion.visible.speed", "outer_wall_speed", ::num)
        intent("plan.mechanism.motion.first_layer.speed", "initial_layer_speed", ::num)
        intent("plan.mechanism.motion.bridge.speed", "bridge_speed", ::num)
        intent("plan.technique.ironing.speed", "ironing_speed", ::num)
        accel("plan.mechanism.motion.visible.acceleration", "outer_wall_acceleration")
        intent("plan.layer.bridge.flow", "bridge_flow", ::num)
        intent("plan.mechanism.adhesion.brim.where", "brim_type") { it }
        intent("plan.mechanism.adhesion.brim.width", "brim_width", ::num)
        intent("plan.mechanism.adhesion.brim.gap", "brim_object_gap", ::num)
        intent("plan.support.enabled", "enable_support", ::bool)
        // the other slicers stop a top surface at what stands on it (their tops are judged against the
        // whole object above): investigation reproduces that
        custom("plan.layer.top.under_parts", emptyList()) { false }
        // Orca's threshold is one angle per print; plymeld's is each filament's share of a line: the
        // share that steps as far per layer as the angle does at the print's layer height and outer
        // wall width (the same step on every one of its uniform layers), for every filament
        custom("hardware.filament.overhang.share", listOf("support_threshold_angle", "layer_height", "outer_wall_line_width")) { x ->
            num(x.slot("layer_height", 0)) / Math.tan(Math.toRadians(num(x.slot("support_threshold_angle", 0)))) / num(x.slot("outer_wall_line_width", 0))
        }
        intent("plan.support.gap.side", "support_object_xy_distance", ::num)
        intent("plan.support.gap.top.value", "support_top_z_distance", ::num)
        intent("plan.support.gap.bottom", "support_bottom_z_distance", ::num)
        intent("plan.support.interface.top_layers", "support_interface_top_layers", ::num)
        intent("plan.support.interface.bottom_layers", "support_interface_bottom_layers", ::num)
        intent("plan.support.spacing", "support_base_pattern_spacing", ::num)
        intent("plan.support.interface.spacing", "support_interface_spacing", ::num)
        intent("plan.support.from_bed_only", "support_on_build_plate_only", ::bool)
        intent("plan.support.line.width", "support_line_width", ::num)
        intent("plan.support.angle", "support_angle", ::num)
        intent("plan.technique.fuzzy_skin.where", "fuzzy_skin") { if (it == "disabled_fuzzy") "none" else it }   // Flash Studio's "off"
        intent("plan.technique.fuzzy_skin.thickness", "fuzzy_skin_thickness", ::num)
        intent("plan.technique.fuzzy_skin.point_distance", "fuzzy_skin_point_distance", ::num)
        intent("plan.technique.fuzzy_skin.first_layer", "fuzzy_skin_first_layer", ::bool)
        custom("plan.technique.fuzzy_skin.mode", listOf("fuzzy_skin_mode")) { x -> if (x.has("fuzzy_skin_mode")) x.slot("fuzzy_skin_mode", 0) else Unit }
        custom("plan.technique.fuzzy_skin.noise", listOf("fuzzy_skin_noise_type")) { x -> if (x.has("fuzzy_skin_noise_type")) x.slot("fuzzy_skin_noise_type", 0) else Unit }
        intent("plan.technique.ironing.type", "ironing_type") { if (it == "no ironing") "none" else it }
        intent("plan.technique.ironing.spacing", "ironing_spacing", ::num)
        intent("plan.technique.ironing.flow", "ironing_flow", ::pct)
        intent("plan.technique.ironing.inset", "ironing_inset", ::num)
        intent("plan.technique.ironing.angle", "ironing_direction", ::num)
        // purge tower: optional (the Q2 projects have no prime_volume); an absent key is left unset
        fun optional(id: String, key: String, conv: (String) -> Any) = custom(id, listOf(key)) { x -> if (x.has(key)) conv(x.slot(key, 0)) else Unit }
        optional("plan.mechanism.purge.tower.enabled", "enable_prime_tower", ::bool)
        optional("plan.mechanism.purge.tower.x", "wipe_tower_x", ::num)
        optional("plan.mechanism.purge.tower.y", "wipe_tower_y", ::num)
        optional("plan.mechanism.purge.tower.width", "prime_tower_width", ::num)
        optional("hardware.filament.purge.volume", "prime_volume", ::num)
        optional("hardware.tool.heat.time", "preheat_time", ::num)
        optional("hardware.machine.tool_change.time", "machine_tool_change_time", ::num)
        // support filaments: 0 = whichever is loaded (the catalog default when a config lacks the key)
        // another slicer's "concentric" is loops following the outline: plymeld's concentric-outside-in (QIDIStudio checked on an
        // oval)
        optional("plan.layer.top.pattern", "top_surface_pattern") { if (it == "concentric") "concentric-outside-in" else it }
        optional("plan.layer.bottom.pattern", "bottom_surface_pattern") { if (it == "concentric") "concentric-outside-in" else it }
        optional("plan.layer.infill.area.min", "minimum_sparse_infill_area", ::num)
        optional("plan.layer.walls.length_factor.min", "min_length_factor", ::num)
        optional("plan.layer.walls.style", "wall_generator") { it }
        optional("plan.support.slot", "support_filament", ::num)
        optional("plan.support.interface.slot", "support_interface_filament", ::num)
        // tree support: optional (QIDIStudio has no tree_support_tip_diameter); read only when tree support is on
        optional("plan.support.tree.branch_angle", "tree_support_branch_angle", ::num)
        optional("plan.support.tree.tip_diameter", "tree_support_tip_diameter", ::num)
        optional("plan.support.tree.branch_diameter", "tree_support_branch_diameter", ::num)
        optional("plan.support.tree.widening", "tree_support_branch_diameter_angle", ::num)
        optional("plan.support.tree.tip_spacing", "tree_support_branch_distance", ::num)
        // idle nozzle temperature: Orca's ooze_prevention holds idle nozzles standby_temperature_delta
        // (negative) away from printing temperature; without it they stay at printing temperature
        custom("hardware.filament.temperature.idle_drop", listOf("ooze_prevention", "standby_temperature_delta")) { x ->
            if (!x.has("ooze_prevention")) Unit
            else if (!bool(x.slot("ooze_prevention", 0))) 0.0
            else -num(x.slot("standby_temperature_delta", 0))
        }

        for (r in rows) Catalog[r.id]   // every row targets a declared setting
    }

    /** The whole import: one machine, its tools, one material + spool per filament slot, one intent, one object. */
    fun setup(c: OrcaConfig, source: String, objectName: String = "model"): Setup {
        val nTools = c.list("nozzle_diameter")?.size ?: error("setting 'nozzle_diameter' missing from the imported config")
        val nFil = c.list("filament_diameter")?.size ?: error("setting 'filament_diameter' missing from the imported config")
        val fmap = c.list("filament_map")
        val notes = ArrayList<String>()
        // Each filament in its own nozzle (a tool changer: not single-extruder multi-material, a
        // nozzle per filament) with an AUTOMATIC map: the saved map is not what gets printed.
        // The user's C5 project saved 1,1,1,1 while Flash Studio's g-code from the same setup used
        // 1,2,3,4 (CREATOR-TEST.3mf vs the color-blender print), so the saved map is
        // stale there; filament N goes in tool N, as that one sliced file did.
        val autoOwnNozzle = nTools > 1 && nFil <= nTools && c["single_extruder_multi_material"] == "0" &&
            (c["filament_map_mode"] ?: "").startsWith("Auto")
        if (autoOwnNozzle && fmap != null && (0 until nFil).any { fmap.getOrNull(it)?.toInt() != it + 1 })
            notes += "filament_map ${fmap.joinToString(",")} ignored: map mode '${c["filament_map_mode"]}' with a nozzle per filament; filament N is in tool N"
        fun toolOf(f: Int): Int = if (nTools == 1) 0 else if (autoOwnNozzle) f else (fmap?.getOrNull(f)?.toInt()?.minus(1)
            ?: error("$nTools tools but no filament_map entry for filament ${f + 1}")).also { require(it in 0 until nTools) { "filament ${f + 1} mapped to tool ${it + 1} of $nTools" } }
        val ids = c.list("filament_settings_id")
        val used = LinkedHashSet<String>()

        fun fill(v: Values, entities: Set<Entity>, f: Int, t: Int) {
            for (r in rows) {
                val s = Catalog[r.id]
                if (s.entity !in entities) continue
                val x = Ctx(c, f, t)
                val value = r.read(x)
                used += x.consulted
                if (value == Unit) continue          // key absent and the catalog has a default for it
                val slot = when (s.entity) { Entity.TOOL -> " [tool ${t + 1}]"; Entity.MACHINE, Entity.INTENT -> ""; else -> " [filament ${f + 1}]" }
                v[r.id] = Sourced(value, "imported: $source, key ${r.keys.filter { it in x.used }.joinToString(" / ")}$slot")
            }
        }
        val machine = Values(Entity.MACHINE, c["printer_settings_id"] ?: c["printer_model"] ?: "machine").also { fill(it, setOf(Entity.MACHINE), 0, 0) }
        val tools = (0 until nTools).map { t -> Values(Entity.TOOL, "tool ${t + 1}").also { fill(it, setOf(Entity.TOOL), 0, t) } }
        val materials = LinkedHashMap<String, Values>()
        val spools = ArrayList<Spool>()
        val mt = LinkedHashMap<Pair<String, Int>, Values>()
        for (f in 0 until nFil) {
            val t = toolOf(f)
            // one material per filament slot: two slots may share a preset name yet differ (user copies)
            val mName = "filament ${f + 1}: ${ids?.getOrNull(f) ?: ids?.firstOrNull() ?: "unnamed"}"
            materials[mName] = Values(Entity.MATERIAL, mName).also { fill(it, setOf(Entity.MATERIAL), f, t) }
            spools += Spool("spool ${f + 1}", mName, t, Values(Entity.SPOOL, "spool ${f + 1}").also { fill(it, setOf(Entity.SPOOL), f, t) })
            mt[mName to t] = Values(Entity.MATERIAL_TOOL, "$mName in tool ${t + 1}").also { fill(it, setOf(Entity.MATERIAL_TOOL), f, t) }
        }
        val intentName = c["print_settings_id"] ?: "imported"
        val intent = Values(Entity.INTENT, intentName).also { fill(it, setOf(Entity.INTENT), 0, 0) }
        val obj = ObjectSetup(objectName, intentName, "spool 1", Values(Entity.INTENT, "object '$objectName' overrides"))
        return Setup(machine, tools, materials, spools, mt, mapOf(intentName to intent), listOf(obj),
            foreignTemplateVars = c.keys.associateWith { c.peek(it)!! },
            importReport = (used + listOfNotNull("filament_map_mode".takeIf { autoOwnNozzle })).let { m -> ImportReport(source, m, c.keys - m, notes) },
            keysOf = ::importedKeys, models = Models.bundled)
    }

    /** The key of the bundled model a profile's printer_model names (its key, or the profile_name it goes by), else the name as it is. */
    fun modelFor(printerModel: String): String = Models.bundled.all.firstOrNull { it.id == printerModel || printerModel in it.otherNames }?.id ?: printerModel

    /** The imported settings' keys a setting is read from, for messages (e.g. "the imported settings' printer_model"); null when none. */
    fun importedKeys(id: String): String? =
        rows.filter { it.id == id }.flatMap { it.keys }.distinct().ifEmpty { null }?.joinToString(" / ")?.let { "the imported settings' $it" }

    /** One object of a project: its setup, and the object's own keys that mapped to nothing in plymeld. */
    class ObjectImport(val setup: ObjectSetup, val unmapped: Set<String>)

    /**
     * An object's own settings from a project (QIDIStudio/Orca `model_settings.config` keys) as
     * overrides of [intent], read through the same rows as the project's settings: the object's keys
     * are laid over [c], and every intent row that reads one of them is read again there.
     * `extruder` (1-based filament slot, Orca's name for it) picks the object's spool.
     */
    fun objectSetup(c: OrcaConfig, source: String, name: String, keys: Map<String, String>, intent: String): ObjectImport {
        val over = keys.entries.fold(c) { acc, (k, v) -> if (k == "extruder") acc else acc.with(k, v) }
        val overrides = Values(Entity.INTENT, "object '$name' overrides")
        val mapped = HashSet<String>()
        for (r in rows) {
            if (Catalog[r.id].entity != Entity.INTENT || r.keys.none { it in keys }) continue
            val x = Ctx(over, 0, 0)
            val value = r.read(x)
            val own = x.used.filter { it in keys }
            mapped += x.consulted.filter { it in keys }
            if (value == Unit || own.isEmpty()) continue
            overrides[r.id] = Sourced(value, "imported: $source, object '$name' key ${own.joinToString(" / ")}")
        }
        val spool = keys["extruder"]?.let { e ->
            val n = e.toIntOrNull() ?: error("object '$name': extruder '$e' is not a number")
            "spool $n".also { mapped += "extruder" }
        } ?: "spool 1"
        return ObjectImport(ObjectSetup(name, intent, spool, overrides), keys.keys - mapped)
    }

    /**
     * A part's own settings (a project's per-volume keys) as overrides, read like the project reads
     * them: the part's keys laid over its object's keys over the config. `extruder` is the part's
     * spool, handled by the caller. Returns the overrides and the keys no plymeld setting reads.
     */
    fun partOverrides(c: OrcaConfig, source: String, objectName: String, objectKeys: Map<String, String>, partName: String,
                      keys: Map<String, String>): Pair<Values, Set<String>> {
        val over = (objectKeys + keys).entries.fold(c) { acc, (k, v) -> if (k == "extruder") acc else acc.with(k, v) }
        val overrides = Values(Entity.INTENT, "part '$partName' overrides")
        val mapped = HashSet<String>()
        for (r in rows) {
            if (Catalog[r.id].entity != Entity.INTENT || r.keys.none { it in keys }) continue
            val x = Ctx(over, 0, 0)
            val value = r.read(x)
            val own = x.used.filter { it in keys }
            mapped += x.consulted.filter { it in keys }
            if (value == Unit || own.isEmpty()) continue
            overrides[r.id] = Sourced(value, "imported: $source, object '$objectName' part '$partName' key ${own.joinToString(" / ")}")
        }
        return overrides to (keys.keys - mapped - "extruder")
    }
}
