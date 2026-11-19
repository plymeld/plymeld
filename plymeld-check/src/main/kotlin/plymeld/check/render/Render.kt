package plymeld.check.render

import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * `plymeld render`: pictures of what a g-code file lays down, read from the file alone (no slicing, no printer). The file
 * is indexed once ([GcodeIndex]), then each picture reads only the layers it draws ([MoveReader]).
 *
 * A line is drawn as wide as the plastic it lays by the bead model ([plymeld.program.Bead]): its cross-section is the
 * filament it feeds per mm times the filament's section, on the layer's thickness. The scale resolves lines: by default,
 * the scale at which the closest lines in view lie at least [LEGIBLE_PX] pixels apart (beads side by side at their spacing,
 * the bead model's), between a picture [MIN_PX] pixels on its longer side and [MAX_PX]. Where that cannot be done, each
 * feature's area is drawn as a flat region with its outline, and the run says which `--span` would resolve the lines.
 */
object Render {
    /** The closest lines lie at least this many pixels apart (center to center) for a picture to resolve them. */
    const val LEGIBLE_PX = 3.0
    /** The longer side of a drawing at the default scale is at least this (plymeld's choice), at most [MAX_PX]. */
    const val MIN_PX = 1000
    /** The largest drawing the default scale makes, pixels on its longer side (plymeld's choice). */
    const val MAX_PX = 4096
    /** The largest drawing any scale makes (plymeld's choice): an image this size is about 1 GB in memory. */
    const val LIMIT_PX = 16384

    /** What a run wrote, and what it has to say about the pictures (lines it could not resolve, what the file does not state). */
    class Result(val files: List<Path>, val notes: List<String>)

    /**
     * Runs [req]: the pictures written ([Result.files]) and the notes. [out] receives each written file's line as the run
     * goes (the path alone with `--print-files`), and a listing's names.
     */
    fun run(req: RenderRequest, out: (String) -> Unit): Result {
        val refused = req.refusals()
        require(refused.isEmpty()) { refused.joinToString("\n") }
        require(Files.isRegularFile(req.file)) { "no such g-code file: ${req.file}" }
        val index = GcodeIndex.read(req.file)
        if (req.listing) { list(index, req, out); return Result(emptyList(), emptyList()) }
        require(index.layers.isNotEmpty()) { "${req.file.fileName} lays no plastic: nothing to draw" }
        val ctx = Context(req, index)
        val views = ctx.views()
        val notes = ArrayList<String>(ctx.notes)
        val dir = req.outDir ?: req.file.toAbsolutePath().resolveSibling(stem(req.file) + ".render")
        Files.createDirectories(dir)
        val written = ArrayList<Path>()
        val pictures = ArrayList<Pair<String, ByteArray>>()
        val frames = if (req.sameFrame) ctx.sharedFrame(views.filterIsInstance<LayerView>()) else null
        for (v in views) {
            val panel = when (v) {
                is LayerView -> ctx.layerPanel(v, frames, notes)
                is CutZView -> ctx.cutZPanel(v, notes)
                is CutView -> ctx.sectionPanel(v, notes)
                else -> error("no picture for $v")
            }
            val bytes = encode(listOf(panel), req)
            val path = dir.resolve(v.fileName + "." + req.format.ext)
            Files.write(path, bytes)
            written.add(path)
            if (req.sheet != null) pictures += v.title to bytes
            val (w, h) = Layout.size(listOf(panel), !req.noAnnotations)
            out(if (req.printFiles) path.toString() else "-> $path (${v.title}, $w x $h px)")
        }
        req.sheet?.let { s ->
            val path = if (s.isAbsolute) s else dir.resolve(s)
            path.parent?.let { Files.createDirectories(it) }
            Files.write(path, Sheet.compose(pictures, req.format, req.file.fileName.toString()))
            written.add(path)
            out(if (req.printFiles) path.toString() else "-> $path (sheet of ${pictures.size} picture(s))")
        }
        return Result(written, notes.distinct())
    }

    internal fun encode(panels: List<Panel>, req: RenderRequest): ByteArray {
        val (w, h) = Layout.size(panels, !req.noAnnotations)
        return if (req.format == Format.PNG) Raster(w, h).also { Layout.draw(it, panels, !req.noAnnotations) }.png()
        else Svg(w, h).also { Layout.draw(it, panels, !req.noAnnotations) }.document().toByteArray(Charsets.UTF_8)
    }

    internal fun stem(p: Path): String = p.fileName.toString().removeSuffix(".3mf").removeSuffix(".gcode")

    private fun list(index: GcodeIndex, req: RenderRequest, out: (String) -> Unit) {
        if (req.listFeatures) {
            if (index.features.size == 0) out("(${req.file.fileName} names no features)")
            for (f in index.features.list) out(f)
        }
        if (req.listObjects) {
            if (index.objects.size == 0) out("(${req.file.fileName} names no objects)")
            for (o in index.objects.list) out(o)
        }
    }

    internal fun f(v: Double, d: Int = 2): String = String.format(Locale.ROOT, "%.${d}f", v)
}

/** One picture a run asks for: its file's name (without the extension) and its title on a sheet. */
internal interface View { val fileName: String; val title: String }

/** A picture of one layer from above (0-based [layer]). */
internal class LayerView(val layer: Int, digits: Int) : View {
    override val fileName = "layer-" + (layer + 1).toString().padStart(maxOf(4, digits), '0')
    override val title = "layer ${layer + 1}"
}

/** A rectangle of the bed, mm. */
internal class Frame(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val w get() = x1 - x0
    val h get() = y1 - y0
}

/** One run's resolved settings over one indexed file: the views, the lines chosen, the widths and colors. */
internal class Context(val req: RenderRequest, val index: GcodeIndex) {
    companion object {
        /** The share of the length laid in view whose lines do not set the scale: the narrowest (plymeld's choice). */
        const val THIN_SHARE = 0.01
        /** A move laying less than this share of its path's declared width is a taper, not a line beside others (plymeld's choice). */
        const val TAPER_SHARE = 0.5
    }
    val notes = ArrayList<String>()
    private val name = req.file.fileName.toString()

    // ---- the lines chosen
    private val objIds: Set<Int>? = ids(req.onlyObject, index.objects, "--only-object", "objects")
    private val featureIds: Set<Int>? = ids(req.onlyFeature, index.features, "--only-feature", "features")
    private val toolIds: Set<Int>? = req.onlyTool.takeIf { it.isNotEmpty() }?.toSet()?.also { ts ->
        val missing = ts - index.tools.toSet()
        require(missing.isEmpty()) { "--only-tool ${missing.sorted().joinToString(",")}: $name lays plastic with tool(s) ${index.tools.joinToString(", ")} only (numbered as the file's T commands, T0 = 0)" }
    }
    init {
        require(req.onlyPart.isEmpty()) { "--only-part ${req.onlyPart.joinToString(",")}: $name names no parts (its objects: --list-objects; --only-object chooses one)" }
    }

    private fun ids(names: List<String>, table: Names, option: String, what: String): Set<Int>? {
        if (names.isEmpty()) return null
        val missing = names.filter { table.find(it) == null }
        require(missing.isEmpty()) {
            if (table.size == 0) "$option ${missing.joinToString(",")}: $name names no $what"
            else "$option ${missing.joinToString(",") { "'$it'" }}: not among the $what $name names: ${table.list.joinToString(", ") { "'$it'" }} (--list-$what)"
        }
        return names.mapTo(HashSet()) { table.find(it)!! }
    }

    fun chosen(m: MoveSet, i: Int): Boolean =
        (objIds == null || m.obj[i] in objIds) && (featureIds == null || m.feature[i] in featureIds) && (toolIds == null || m.tool[i] in toolIds)
    /** A travel or mark is chosen by its object and tool (a travel carries the feature it came from). */
    fun chosenPlace(obj: Int, tool: Int): Boolean = (objIds == null || obj in objIds) && (toolIds == null || tool in toolIds)

    // ---- widths
    /** Each tool's filament diameter (mm): the file's own statement, else the source block's. */
    private fun diameter(tool: Int): Double? =
        index.diameters.getOrNull(tool) ?: index.diameters.singleOrNull() ?: req.facts.diameters[tool]
    private val anyDiameter = index.tools.isNotEmpty() && index.tools.all { diameter(it) != null }
    private val diameterWords: String = index.tools.mapNotNull { t -> diameter(t)?.let { "T$t ${Layout.mm(it)} mm" } }.distinct().joinToString(", ") +
        (if (index.diameters.isEmpty() && req.facts.source.isNotEmpty()) " (from ${req.facts.source})" else "")

    val lineWidth: LineWidth = when (req.lineWidth) {
        LineWidth.PLASTIC -> { require(anyDiameter) { "--line-width plastic needs each filament's diameter, which $name does not state${missingTools()}: use --line-width declared or center" }; LineWidth.PLASTIC }
        LineWidth.DECLARED -> { require(index.declaresWidths) { "--line-width declared: $name declares no line widths (no ;WIDTH: or ; LINE_WIDTH: lines): use --line-width plastic or center" }; LineWidth.DECLARED }
        LineWidth.CENTER -> LineWidth.CENTER
        null -> when {
            anyDiameter -> LineWidth.PLASTIC
            index.declaresWidths -> { notes += "$name states no filament diameter${missingTools()}: lines drawn as wide as the file declares (--line-width declared), not as the plastic they lay"; LineWidth.DECLARED }
            else -> { notes += "$name states neither a filament diameter nor line widths: lines drawn as thin center lines (--line-width center)"; LineWidth.CENTER }
        }
    }
    private fun missingTools(): String { val m = index.tools.filter { diameter(it) == null }; return if (m.isEmpty() || m.size == index.tools.size) "" else " for tool(s) ${m.joinToString(", ")}" }

    /** The layer thickness a move is laid at: its declared height, else its layer's. */
    fun heightOf(m: MoveSet, i: Int): Double = m.hDecl[i].toDouble().takeIf { !it.isNaN() && it > 0 } ?: index.layers.getOrNull(m.layer[i])?.height?.takeIf { it > 0 } ?: Double.NaN

    /** The width (mm) of the bead move [i] lays: from its plastic by the bead model, else as declared; NaN when unknown. */
    fun beadWidth(m: MoveSet, i: Int): Double {
        val len = m.lengthXY(i)
        val d = diameter(m.tool[i])
        val h = heightOf(m, i)
        if (d != null && len > 1e-9 && !h.isNaN()) return plymeld.program.Bead.width(m.e[i] * Math.PI * d * d / 4 / len, h)
        return m.wDecl[i].toDouble()
    }
    /** The width move [i] is drawn at (mm): the bead's or the declared width, as --line-width says; NaN for a center line. */
    fun drawWidth(m: MoveSet, i: Int): Double = when (lineWidth) {
        LineWidth.PLASTIC -> beadWidth(m, i)
        LineWidth.DECLARED -> m.wDecl[i].toDouble()
        LineWidth.CENTER -> Double.NaN
    }

    // ---- colors
    val colorBy: ColorBy = run {
        val c = req.colorBy
        if (c == ColorBy.FEATURE) require(index.namesFeatures) { "--color-by feature: $name names no features (no ;TYPE: or ; FEATURE: lines): use another --color-by, such as tool or speed" }
        if (c == null && !index.namesFeatures) {
            notes += "$name names no features (no ;TYPE: or ; FEATURE: lines): every line drawn in one color (--color-by feature, the default)"
        }
        c ?: ColorBy.FEATURE
    }
    private val unknownFeatureRank = HashMap<Int, Int>().also { m ->
        var k = 0
        for ((i, n) in index.features.list.withIndex()) if (plymeld.check.GcodeRender.colors[n] == null && plymeld.check.TechniqueRender.EXTRA[n] == null) m[i] = k++
    }
    private val statedColors: Map<Int, String> = buildMap {
        for ((t, c) in req.facts.colors) put(t, c)
        for ((t, c) in index.colors.withIndex()) if (c.isNotEmpty()) put(t, c)
    }
    fun featureColor(id: Int): Color = Palette.feature(if (id < 0) null else index.features[id], id) { unknownFeatureRank[it] ?: 0 }
    fun toolColor(t: Int): Color = Palette.tool(t, statedColors)

    /** A move's value on a gradient, in the unit [unit] names; NaN where the file does not say. */
    fun value(m: MoveSet, i: Int, order: Int): Double = when (colorBy) {
        ColorBy.SPEED -> m.speed[i].toDouble()
        ColorBy.FLOW -> { val d = diameter(m.tool[i]); val len = m.lengthXY(i)
            if (len <= 1e-9) Double.NaN else if (d != null) m.e[i] * Math.PI * d * d / 4 / len * m.speed[i] else m.e[i] / len * m.speed[i] }
        ColorBy.WIDTH -> if (lineWidth == LineWidth.CENTER) beadWidth(m, i) else drawWidth(m, i)
        ColorBy.HEIGHT -> heightOf(m, i)
        ColorBy.FAN -> m.fan[i] * 100.0
        ColorBy.TEMPERATURE -> m.temp[i].toDouble()
        ColorBy.ORDER -> order.toDouble()
        ColorBy.LAYER_TIME -> index.layers.getOrNull(m.layer[i])?.nominalS ?: Double.NaN
        ColorBy.FEATURE, ColorBy.TOOL -> Double.NaN
    }
    fun unit(): String = when (colorBy) {
        ColorBy.SPEED -> "mm/s"
        ColorBy.FLOW -> if (anyDiameter) "mm³/s" else "mm of filament/s"
        ColorBy.WIDTH, ColorBy.HEIGHT -> "mm"
        ColorBy.FAN -> "% part fan"
        ColorBy.TEMPERATURE -> "C"
        ColorBy.ORDER -> ""
        ColorBy.LAYER_TIME -> "s"
        else -> ""
    }
    fun colorWords(): String = when (colorBy) {
        ColorBy.FEATURE -> if (index.namesFeatures) "colored by feature" else "one color (the file names no features)"
        ColorBy.TOOL -> "colored by tool" + if (statedColors.isNotEmpty()) " (the filaments' colors as ${if (index.colors.isNotEmpty()) "the file" else req.facts.source} states them)" else " (plymeld's colors: the file states no filament colors)"
        ColorBy.SPEED -> "colored by speed (the commanded feed rate)"
        ColorBy.FLOW -> if (anyDiameter) "colored by flow (plastic per second: the bead's cross-section times its speed)" else "colored by flow (filament fed per second: the file states no filament diameter)"
        ColorBy.WIDTH -> "colored by width (${if (lineWidth == LineWidth.DECLARED) "as declared" else "from the plastic, by the bead model"})"
        ColorBy.HEIGHT -> "colored by layer thickness (as declared, else from the layers' heights)"
        ColorBy.FAN -> "colored by part fan"
        ColorBy.TEMPERATURE -> "colored by nozzle temperature"
        ColorBy.ORDER -> "colored by print order, first to last in this picture"
        ColorBy.LAYER_TIME -> "colored by the layer's time at its commanded speeds (acceleration not counted)"
    }
    fun widthWords(): String = when (lineWidth) {
        LineWidth.PLASTIC -> "lines as wide as the plastic each lays (bead model; filament $diameterWords), edges darker"
        LineWidth.DECLARED -> "lines as wide as the file declares, edges darker"
        LineWidth.CENTER -> "lines as thin center lines"
    }

    // ---- views
    fun views(): List<View> {
        val n = index.layers.size
        val digits = n.toString().length
        val out = LinkedHashSet<Int>()
        val cuts = ArrayList<View>()
        for (z in req.cutZ) cuts += CutZView(z)
        for (y in req.cutY) cuts += Cuts.y(y)
        for (x in req.cutX) cuts += Cuts.x(x)
        for (l in req.cutLine) cuts += Cuts.line(l[0], l[1], l[2], l[3])
        for ((p, d) in req.cutPlane) {
            val l = Math.sqrt(d.sumOf { it * it })
            if (Math.abs(d[2]) >= 0.999999 * l) {
                require(d[2] > 0) { "--cut-plane ${p.joinToString(",")}:${d.joinToString(",")}: a level plane facing down; a level cut is seen from above: --cut-z ${Layout.mm(p[2])}" }
                cuts += CutZView(p[2])
            } else cuts += Cuts.plane(p, d)
        }
        for (k in req.layers) { require(k <= n) { "--layer $k: $name has $n layers (the first is 1)" }; out += k - 1 }
        for (z in req.layersAtZ) out += (index.layerNumberAtZ(z) ?: error("--layer-at-z ${Layout.mm(z)}: no layer of $name reaches that height (its top layer is at z ${Layout.mm(index.layers.last().z)} mm)")) - 1
        for (r in req.layerRanges) {
            require(r.first <= n) { "--layer-range ${r.first}..${r.last}: $name has $n layers (the first is 1)" }
            for (k in r.first..minOf(r.last, n) step (req.layerStep ?: 1)) out += k - 1
            if (r.last > n) notes += "--layer-range ${r.first}..${r.last}: $name has $n layers; drawn to layer $n"
        }
        if (out.isEmpty() && cuts.isEmpty()) out += (n - 1) / 2
        return out.map { LayerView(it, digits) } + cuts.distinctBy { it.fileName }
    }

    /** The layers a picture of [layer] reads: it, and those --layers-below adds. */
    private fun readLayers(layer: Int): List<Int> = ((layer - (req.layersBelow ?: 0)).coerceAtLeast(0)..layer).toList()

    /** The frame a view draws: --bed-window, --around and --span, else its chosen lines' extent with a margin. */
    fun frameOf(m: MoveSet, layer: Int): Frame {
        req.bedWindow?.let { return Frame(it[0], it[1], it[2], it[3]) }
        req.around?.let { (x, y) -> val s = req.span!! / 2; return Frame(x - s, y - s, x + s, y + s) }
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in 0 until m.n) if (m.kind[i] == MoveSet.EXTRUDE && (layer < 0 || m.layer[i] == layer) && chosen(m, i)) {
            val w = beadWidth(m, i).takeIf { !it.isNaN() } ?: 0.0
            x0 = minOf(x0, m.x0[i] - w, m.x1[i] - w); x1 = maxOf(x1, m.x0[i] + w, m.x1[i] + w)
            y0 = minOf(y0, m.y0[i] - w, m.y1[i] - w); y1 = maxOf(y1, m.y0[i] + w, m.y1[i] + w)
        }
        if (x0 > x1) { val l = index.layers.getOrNull(layer); if (l == null || l.minX.isNaN()) return Frame(0.0, 0.0, 10.0, 10.0); x0 = l.minX; y0 = l.minY; x1 = l.maxX; y1 = l.maxY }
        val pad = maxOf(1.0, 0.04 * maxOf(x1 - x0, y1 - y0))
        return Frame(x0 - pad, y0 - pad, x1 + pad, y1 + pad)
    }

    /**
     * The closest lines in view: beads side by side at their spacing (the bead model's), over the chosen extruding moves in
     * [f], leaving out the narrowest [THIN_SHARE] of the length laid there (a scarf's ramp, a line's tapered end: lines
     * laid on or into another, not beside it); NaN when no width is known.
     */
    fun closest(m: MoveSet, layer: Int, f: Frame): Double {
        val sp = ArrayList<Double>(); val len = ArrayList<Double>()
        for (i in 0 until m.n) {
            if (m.kind[i] != MoveSet.EXTRUDE || (layer >= 0 && m.layer[i] != layer) || !chosen(m, i)) continue
            if (maxOf(m.x0[i], m.x1[i]) < f.x0 || minOf(m.x0[i], m.x1[i]) > f.x1 || maxOf(m.y0[i], m.y1[i]) < f.y0 || minOf(m.y0[i], m.y1[i]) > f.y1) continue
            val w = beadWidth(m, i); val h = heightOf(m, i)
            if (w.isNaN() || w <= 0) continue
            // a move laying less than half its path's declared width is a taper or a ramp (a line's end, a scarf), laid
            // on or into its neighbor rather than beside it
            val decl = m.wDecl[i].toDouble()
            if (!decl.isNaN() && w < TAPER_SHARE * decl) continue
            val s = if (h.isNaN()) w else plymeld.program.Bead.spacing(w, h)
            if (s > 0) { sp += s; len += m.lengthXY(i) }
        }
        if (sp.isEmpty()) return Double.NaN
        val order = sp.indices.sortedWith(compareBy<Int> { sp[it] }.thenBy { it })
        val total = len.sum()
        var acc = 0.0
        for (k in order) { acc += len[k]; if (acc > THIN_SHARE * total) return sp[k] }
        return sp[order.last()]
    }

    /** The shared frame and closest spacing of every view (--same-frame). */
    fun sharedFrame(views: List<LayerView>): Pair<Frame, Double> {
        var fr: Frame? = null; var sp = Double.NaN
        for (v in views) {
            val (m, _) = MoveReader.read(index, listOf(v.layer))
            val f = frameOf(m, v.layer)
            fr = fr?.let { Frame(minOf(it.x0, f.x0), minOf(it.y0, f.y0), maxOf(it.x1, f.x1), maxOf(it.y1, f.y1)) } ?: f
            val s = closest(m, v.layer, f)
            if (!s.isNaN() && (sp.isNaN() || s < sp)) sp = s
        }
        return fr!! to sp
    }

    /** The scale (px per mm) for frame [f] with lines [spacing] apart, and whether it resolves them. */
    fun scale(f: Frame, spacing: Double, what: String, notes: MutableList<String>): Pair<Double, Boolean> {
        val longer = maxOf(f.w, f.h)
        val s = when {
            req.pxPerMm != null -> req.pxPerMm
            req.pictureSize != null -> minOf(req.pictureSize.first / f.w, req.pictureSize.second / f.h)
            else -> {
                val need = if (spacing.isNaN()) 0.0 else Render.LEGIBLE_PX / spacing
                minOf(maxOf(need, Render.MIN_PX / longer), Render.MAX_PX / longer)
            }
        }
        require(longer * s <= Render.LIMIT_PX) { "$what would be ${Math.ceil(longer * s).toInt()} px across at ${Render.f(s, 1)} px per mm, more than ${Render.LIMIT_PX}: frame less of the bed (--around X,Y --span MM, or --bed-window) or give a smaller --px-per-mm" }
        val resolved = spacing.isNaN() || spacing * s >= Render.LEGIBLE_PX - 1e-9
        if (!resolved) {
            val span = Math.floor(Render.MAX_PX * spacing / Render.LEGIBLE_PX)
            notes += "$what: its closest lines lie ${Render.f(spacing, 3)} mm apart and need ${Render.f(Render.LEGIBLE_PX / spacing, 1)} px per mm; " +
                (if (req.pxPerMm != null || req.pictureSize != null) "the scale given is ${Render.f(s, 1)}" else "at that scale the ${Render.f(longer, 1)} mm frame would be more than ${Render.MAX_PX} px") +
                (if (req.allowUnresolved) ": lines drawn anyway (--allow-unresolved-lines)" else ": drawn as flat regions with their outlines") +
                (if (req.pxPerMm != null || req.pictureSize != null) "; --px-per-mm ${Render.f(Math.ceil(Render.LEGIBLE_PX / spacing * 10) / 10, 1)} or more resolves them"
                 else "; --around X,Y --span ${Layout.mm(span)} resolves them")
        }
        return s to resolved
    }

    /** The picture of [v]: its layer from above. */
    fun layerPanel(v: LayerView, shared: Pair<Frame, Double>?, notes: MutableList<String>): Panel {
        val (m, marks) = MoveReader.read(index, readLayers(v.layer))
        val frame = shared?.first ?: frameOf(m, v.layer)
        val spacing = shared?.second ?: closest(m, v.layer, frame)
        val l = index.layers[v.layer]
        val what = "${name}, layer ${v.layer + 1}"
        val (s, resolved) = scale(frame, spacing, what, notes)
        val asLines = resolved || req.allowUnresolved
        val dw = maxOf(1, Math.round(frame.w * s).toInt()); val dh = maxOf(1, Math.round(frame.h * s).toInt())
        val top = TopDrawing(this, m, marks, v.layer, frame, s, asLines)
        val caption = ArrayList<String>()
        caption += "$name: layer ${v.layer + 1} of ${index.layers.size}, seen from above"
        caption += "Its top at z ${Render.f(l.z)} mm, ${Render.f(l.height)} mm thick; X across, Y up; ${Render.f(s, 2)} px per mm; " +
            "bed X ${Render.f(frame.x0, 1)} to ${Render.f(frame.x1, 1)}, Y ${Render.f(frame.y0, 1)} to ${Render.f(frame.y1, 1)} mm" + (if (req.sameFrame) " (one frame for every layer of the run)" else "") + "."
        caption += (if (asLines) widthWords() else "each feature's area as a flat region with its outline (its lines ${Render.f(spacing, 3)} mm apart need ${Render.f(Render.LEGIBLE_PX / spacing, 1)} px per mm)") +
            "; ${colorWords()}" + top.extraWords() + "."
        if (!index.layerMarkers) caption += "Layers read from the heights the moves are laid at: the file marks none."
        if (top.empty) caption += "No line of this layer is chosen by the --only-... options."
        return Panel(dw, dh, caption, top.key(), s, plymeld.check.TechniqueRender.niceBar(frame.w), draw = { c, ox, oy -> top.draw(c, ox, oy, dw, dh) })
    }

    /** The picture of a level cut at [v]'s height, seen from above: each bead the plane crosses, as wide as it is there. */
    fun cutZPanel(v: CutZView, notes: MutableList<String>): Panel {
        val z = v.z; val d = req.cutDepth ?: 0.0
        val layers = index.layers.filter { l -> !l.minZ.isNaN() && l.maxZ >= z - d && l.minZ <= z + maxOf(l.height, 0.0) + d }.map { it.index }
        val (m, _) = MoveReader.read(index, layers) { _, _, a, _, _, b -> minOf(a, b) <= z + 5 + d && maxOf(a, b) >= z - d }
        val cm = MoveSet(); val widths = ArrayList<Double>(); val nearFlags = ArrayList<Boolean>()
        val from = java.util.TreeSet<Int>()
        for (i in 0 until m.n) {
            if (m.kind[i] != MoveSet.EXTRUDE) continue
            val h = heightOf(m, i); val w0 = drawWidth(m, i).takeIf { !it.isNaN() } ?: beadWidth(m, i)
            if (h.isNaN() || w0.isNaN()) continue
            val za = m.z0[i]; val zb = m.z1[i]
            // the part of the move whose bead (from the nozzle down its height) the plane crosses: the nozzle within [z, z + h]
            var t0 = 0.0; var t1 = 1.0
            if (Math.abs(zb - za) < 1e-12) { if (za < z || za > z + h) { t0 = 1.0; t1 = 0.0 } }
            else { val ta = (z - za) / (zb - za); val tb = (z + h - za) / (zb - za); t0 = maxOf(0.0, minOf(ta, tb)); t1 = minOf(1.0, maxOf(ta, tb)) }
            fun piece(a: Double, b: Double, width: Double, near: Boolean) {
                val dx = m.x1[i] - m.x0[i]; val dy = m.y1[i] - m.y0[i]
                cm.addFrom(m, i, m.x0[i] + a * dx, m.y0[i] + a * dy, za + a * (zb - za), m.x0[i] + b * dx, m.y0[i] + b * dy, za + b * (zb - za), m.e[i] * (b - a))
                widths += width; nearFlags += near
            }
            if (t1 > t0 + 1e-12) {
                val zc = za + (t0 + t1) / 2 * (zb - za)
                piece(t0, t1, 2 * Cuts.halfWidthAt(w0, h, z - (zc - h)), false)
                from += m.layer[i]
            } else if (d > 0) {
                // not crossing: within the depth when the bead's height range comes within it of the plane
                val lo = minOf(za, zb) - h; val hi = maxOf(za, zb)
                if (hi >= z - d && lo <= z + d) piece(0.0, 1.0, w0, true)
            }
        }
        val frame = frameOf(cm, -1)
        val spacing = closest(cm, -1, frame)
        val what = "$name, ${v.title}"
        val (s, resolved) = scale(frame, spacing, what, notes)
        val asLines = resolved || req.allowUnresolved
        val dw = maxOf(1, Math.round(frame.w * s).toInt()); val dh = maxOf(1, Math.round(frame.h * s).toInt())
        val top = TopDrawing(this, cm, MarkSet(), -1, frame, s, asLines, widths.toDoubleArray(), nearFlags.toBooleanArray())
        val layerWords = if (from.isEmpty()) "no layer's beads" else if (from.size == 1) "layer ${from.first() + 1}'s beads" else "beads of ${from.size} layers (${from.first() + 1} to ${from.last() + 1})"
        val caption = ArrayList<String>()
        caption += "$name: cut by the level plane z = ${Cuts.n3(z)} mm, seen from above"
        caption += "Not a layer: it meets $layerWords, each drawn as wide as its bead is at that height (bead model). X across, Y up; ${Render.f(s, 2)} px per mm; " +
            "bed X ${Render.f(frame.x0, 1)} to ${Render.f(frame.x1, 1)}, Y ${Render.f(frame.y0, 1)} to ${Render.f(frame.y1, 1)} mm."
        caption += (if (asLines) "edges darker" else "each feature's area as a flat region with its outline (its lines ${Render.f(spacing, 3)} mm apart need ${Render.f(Render.LEGIBLE_PX / spacing, 1)} px per mm)") +
            "; ${colorWords()}" + (if (d > 0) "; the beads within ${Layout.mm(d)} mm of the plane that it does not cross, lighter" else "") + top.extraWords() + "."
        if (top.empty) caption += "No line crosses this plane" + (if (anyOnlyWords()) " among those the --only-... options choose." else ".")
        return Panel(dw, dh, caption, top.key(), s, plymeld.check.TechniqueRender.niceBar(frame.w), draw = { c, ox, oy -> top.draw(c, ox, oy, dw, dh) })
    }

    private fun anyOnlyWords() = req.anyOnly

    /** The picture of a cut seen from the side ([v]'s plane): each crossing bead's cross-section, heights stretched so a layer reads. */
    fun sectionPanel(v: CutView, notes: MutableList<String>): Panel {
        val pl = v.plane
        val zr = req.zRange
        val d = req.cutDepth
        val layers = index.layers.filter { l -> !l.minZ.isNaN() && (zr == null || (l.maxZ >= zr.start && l.minZ - maxOf(l.height, 0.0) <= zr.endInclusive)) }.map { it.index }
        val margin = (d ?: 0.0) + 1.0
        val (m, _) = MoveReader.read(index, layers) { x0, y0, z0, x1, y1, z1 ->
            val a = pl.dist(x0, y0, z0); val b = pl.dist(x1, y1, z1); a * b <= 0 || minOf(Math.abs(a), Math.abs(b)) <= margin }
        val sd = SectionDrawing(this, m, v, d)
        val ext = sd.extent()
        var u0: Double; var u1: Double; var v0: Double; var v1: Double
        if (ext == null) { u0 = 0.0; u1 = 10.0; v0 = 0.0; v1 = 1.0 } else { u0 = ext[0]; v0 = ext[1]; u1 = ext[2]; v1 = ext[3] }
        val pad = maxOf(0.5, 0.03 * (u1 - u0))
        u0 -= pad; u1 += pad
        val vpad = maxOf(0.2, 0.03 * (v1 - v0)); v0 -= vpad; v1 += vpad
        val heightUp = pl.vz > 0.999999
        if (zr != null && heightUp) { v0 = zr.start - pl.pz; v1 = zr.endInclusive - pl.pz }
        val spacing = sd.closest()
        val what = "$name, ${v.title}"
        val (s, resolved) = scale(Frame(u0, v0, u1, v1), spacing, what, notes)
        val asLines = resolved || req.allowUnresolved
        val hMin = sd.heightMin
        var k = req.heightStretch ?: (if (hMin.isNaN()) 1.0 else maxOf(1.0, Render.LEGIBLE_PX / (hMin * s)))
        val maxH = if (req.pictureSize != null) req.pictureSize.second.toDouble() else Render.MAX_PX.toDouble()
        if (req.heightStretch == null && (v1 - v0) * s * k > maxH) {
            k = maxOf(1.0, maxH / ((v1 - v0) * s))
            notes += "$what: its heights stretched ${Render.f(k, 2)} times, all a picture ${maxH.toInt()} px tall holds: a layer ${Render.f(hMin, 2)} mm thick is drawn ${Render.f(hMin * s * k, 1)} px tall (--z-range draws fewer heights, taller)"
        }
        require((v1 - v0) * s * k <= Render.LIMIT_PX) { "$what would be ${Math.ceil((v1 - v0) * s * k).toInt()} px tall, more than ${Render.LIMIT_PX}: give a smaller --height-stretch, or --z-range" }
        val dw = maxOf(1, Math.round((u1 - u0) * s).toInt()); val dh = maxOf(1, Math.round((v1 - v0) * s * k).toInt())
        val ids = sd.moveIds
        val coloring = Coloring(this, m, ids)
        val caption = ArrayList<String>()
        caption += "$name: cut by ${v.words}"
        caption += "${v.axes}; ${Render.f(s, 2)} px per mm across; " +
            (if (Math.abs(k - 1) < 1e-9) "heights in true proportion" else "heights drawn ${Render.f(k, 2)} times their true size") +
            "; ${v.acrossName} ${Render.f(u0, 1)} to ${Render.f(u1, 1)}, ${if (heightUp && pl.pz == 0.0) "height" else "up"} ${Render.f(v0, 2)} to ${Render.f(v1, 2)} mm" +
            (if (zr != null) " (--z-range)" else "") + "."
        caption += "${ids.size} bead section(s): each line crossing the plane drawn as its bead's cross-section where the plane meets it (bead model; " +
            (if (lineWidth == LineWidth.PLASTIC) "filament $diameterWords" else "its width as declared") + ")" + (if (asLines) ", outlined darker" else ", as flat regions with their outlines") +
            "; ${colorWords()}" + (if (d != null) "; the lines within ${Layout.mm(d)} mm of the plane, from the side and lighter (${sd.alongCount})" else "") + onlyWords() + "."
        return Panel(dw, dh, caption, coloring.key(), s, plymeld.check.TechniqueRender.niceBar(u1 - u0), s * k, plymeld.check.TechniqueRender.niceBar(v1 - v0),
            draw = { c, ox, oy -> sd.draw(c, coloring::color, { u -> ox + (u - u0) * s }, { vv -> oy + (v1 - vv) * s * k }, asLines, dw, dh, ox, oy) })
    }

    fun onlyWords(): String {
        val parts = listOfNotNull(
            req.onlyObject.takeIf { it.isNotEmpty() }?.let { "object ${it.joinToString(" or ")}" },
            req.onlyTool.takeIf { it.isNotEmpty() }?.let { "tool ${it.joinToString(" or ") { t -> "T$t" }}" },
            req.onlyFeature.takeIf { it.isNotEmpty() }?.let { "feature ${it.joinToString(" or ")}" })
        if (parts.isEmpty()) return ""
        return "; only lines of ${parts.joinToString(", and ")}, " + (if (req.others == Others.FAINT) "the others in gray" else "the others hidden")
    }
}
