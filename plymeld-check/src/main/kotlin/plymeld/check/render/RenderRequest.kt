package plymeld.check.render

import java.nio.file.Path

/** What a line's color says. */
enum class ColorBy(val word: String) {
    FEATURE("feature"), TOOL("tool"), SPEED("speed"), FLOW("flow"), WIDTH("width"), HEIGHT("height"), FAN("fan"),
    TEMPERATURE("temperature"), ORDER("order"), LAYER_TIME("layer-time");
    companion object { fun of(w: String) = entries.firstOrNull { it.word == w } }
}

/** How wide a line is drawn: as the plastic it lays, as the file declares, or as a thin center line. */
enum class LineWidth(val word: String) { PLASTIC("plastic"), DECLARED("declared"), CENTER("center") }

/** Marks added to the lines. */
enum class Show(val word: String) { TRAVELS("travels"), SEAMS("seams"), RETRACTIONS("retractions"), STARTS("starts"), TOOL_CHANGES("tool-changes") }

/** What becomes of the lines an `--only-...` option does not choose. */
enum class Others(val word: String) { HIDE("hide"), FAINT("faint") }

enum class Format(val word: String, val ext: String) { PNG("png", "png"), SVG("svg", "svg") }

/**
 * What the file's own text does not say but its source block does (read by the command, which knows plymeld's config):
 * each tool's filament diameter (mm) and color (#RRGGBB), by tool number; null where unknown.
 */
class FileFacts(val diameters: Map<Int, Double> = emptyMap(), val colors: Map<Int, String> = emptyMap(), val source: String = "")

/**
 * One run of `plymeld render`: the pictures asked for (the view options), the lines chosen, how they are drawn and framed,
 * and where the pictures go. A field left null was not given; [refusals] names every option that applies to no picture
 * asked for, or that cannot hold with another, before anything is read.
 */
class RenderRequest(
    val file: Path,
    // views
    val layers: List<Int> = emptyList(),
    val layersAtZ: List<Double> = emptyList(),
    val layerRanges: List<IntRange> = emptyList(),
    val layerStep: Int? = null,
    val cutZ: List<Double> = emptyList(),
    val cutY: List<Double> = emptyList(),
    val cutX: List<Double> = emptyList(),
    /** Each `--cut-line`: x0, y0, x1, y1. */
    val cutLine: List<DoubleArray> = emptyList(),
    /** Each `--cut-plane`: a point and a direction. */
    val cutPlane: List<Pair<DoubleArray, DoubleArray>> = emptyList(),
    val cutDepth: Double? = null,
    val zRange: ClosedFloatingPointRange<Double>? = null,
    val heightStretch: Double? = null,
    /** Each `--outside` side: front, back, left, right, or top. */
    val outside: List<String> = emptyList(),
    val threeD: Boolean = false,
    /** Each `--3d-from`: turn and tilt, degrees. */
    val threeDFrom: List<Pair<Double, Double>> = emptyList(),
    // choosing lines
    val onlyObject: List<String> = emptyList(),
    val onlyPart: List<String> = emptyList(),
    val onlyTool: List<Int> = emptyList(),
    val onlyFeature: List<String> = emptyList(),
    val others: Others? = null,
    // what is drawn
    val colorBy: ColorBy? = null,
    val lineWidth: LineWidth? = null,
    val show: Set<Show> = emptySet(),
    val layersBelow: Int? = null,
    // framing and size
    val bedWindow: DoubleArray? = null,
    val around: Pair<Double, Double>? = null,
    val span: Double? = null,
    val sameFrame: Boolean = false,
    val pxPerMm: Double? = null,
    val pictureSize: Pair<Int, Int>? = null,
    val allowUnresolved: Boolean = false,
    val noAnnotations: Boolean = false,
    // outputs
    val outDir: Path? = null,
    val format: Format = Format.PNG,
    val sheet: Path? = null,
    val printFiles: Boolean = false,
    /** `--compare-with`: the other file, and what its source block says. */
    val compareWith: Path? = null,
    val compareFacts: FileFacts = FileFacts(),
    val listFeatures: Boolean = false,
    val listObjects: Boolean = false,
    val facts: FileFacts = FileFacts(),
) {
    val listing: Boolean get() = listFeatures || listObjects
    /** The cuts seen from the side (every cut but the level `--cut-z`, which is seen from above). */
    val sideCuts: Boolean get() = cutY.isNotEmpty() || cutX.isNotEmpty() || cutLine.isNotEmpty() || cutPlane.any { Math.abs(it.second[2]) < 0.999999 * Math.sqrt(it.second.sumOf { v -> v * v }) }
    val anyCut: Boolean get() = cutZ.isNotEmpty() || cutY.isNotEmpty() || cutX.isNotEmpty() || cutLine.isNotEmpty() || cutPlane.isNotEmpty()
    /** Views of the whole print: the outside from a side, or 3D. */
    val anySolid: Boolean get() = outside.isNotEmpty() || threeD
    /** Pictures whose up is height: cuts seen from the side and the outside from a side (not from the top). */
    val sideViews: Boolean get() = sideCuts || outside.any { it != "top" }
    /** Whether the run draws a layer picture: one asked for, or the middle layer when no view is. */
    val layerViews: Boolean get() = layers.isNotEmpty() || layersAtZ.isNotEmpty() || layerRanges.isNotEmpty() || !(anyCut || anySolid)

    /** The same options for [other] (`--compare-with`): its own facts, and nothing to compare it with. */
    fun forOther(other: Path, otherFacts: FileFacts) = RenderRequest(other, layers, layersAtZ, layerRanges, layerStep, cutZ, cutY, cutX, cutLine, cutPlane,
        cutDepth, zRange, heightStretch, outside, threeD, threeDFrom, onlyObject, onlyPart, onlyTool, onlyFeature, others, colorBy, lineWidth, show,
        layersBelow, bedWindow, around, span, sameFrame, pxPerMm, pictureSize, allowUnresolved, noAnnotations, outDir, format, sheet, printFiles,
        null, FileFacts(), listFeatures, listObjects, otherFacts)
    val anyOnly: Boolean get() = onlyObject.isNotEmpty() || onlyPart.isNotEmpty() || onlyTool.isNotEmpty() || onlyFeature.isNotEmpty()

    /** The options given that draw or frame a picture, by their names, for a listing to refuse. */
    private fun pictureOptions(): List<String> = listOfNotNull(
        "--layer".takeIf { layers.isNotEmpty() }, "--layer-at-z".takeIf { layersAtZ.isNotEmpty() }, "--layer-range".takeIf { layerRanges.isNotEmpty() },
        "--layer-step".takeIf { layerStep != null }, "--cut-z".takeIf { cutZ.isNotEmpty() }, "--cut-y".takeIf { cutY.isNotEmpty() },
        "--cut-x".takeIf { cutX.isNotEmpty() }, "--cut-line".takeIf { cutLine.isNotEmpty() }, "--cut-plane".takeIf { cutPlane.isNotEmpty() },
        "--cut-depth".takeIf { cutDepth != null }, "--z-range".takeIf { zRange != null }, "--height-stretch".takeIf { heightStretch != null },
        "--outside".takeIf { outside.isNotEmpty() }, "--3d".takeIf { threeD }, "--3d-from".takeIf { threeDFrom.isNotEmpty() },
        "--compare-with".takeIf { compareWith != null },
        "--only-object".takeIf { onlyObject.isNotEmpty() }, "--only-part".takeIf { onlyPart.isNotEmpty() },
        "--only-tool".takeIf { onlyTool.isNotEmpty() }, "--only-feature".takeIf { onlyFeature.isNotEmpty() }, "--others".takeIf { others != null },
        "--color-by".takeIf { colorBy != null }, "--line-width".takeIf { lineWidth != null }, "--show".takeIf { show.isNotEmpty() },
        "--layers-below".takeIf { layersBelow != null }, "--bed-window".takeIf { bedWindow != null }, "--around".takeIf { around != null },
        "--span".takeIf { span != null }, "--same-frame".takeIf { sameFrame }, "--px-per-mm".takeIf { pxPerMm != null },
        "--picture-size".takeIf { pictureSize != null }, "--allow-unresolved-lines".takeIf { allowUnresolved }, "--no-annotations".takeIf { noAnnotations },
        "--output".takeIf { outDir != null }, "--format".takeIf { format != Format.PNG }, "--sheet".takeIf { sheet != null }, "--print-files".takeIf { printFiles })

    /** Every refusal of the options as given (rule: nothing is silently ignored), each naming the options involved. */
    fun refusals(): List<String> {
        val out = ArrayList<String>()
        if (listing) {
            val drawn = pictureOptions()
            val list = listOfNotNull("--list-features".takeIf { listFeatures }, "--list-objects".takeIf { listObjects }).joinToString(" and ")
            if (drawn.isNotEmpty()) out += "$list print names and draw nothing: not with ${drawn.joinToString(", ")}"
            return out
        }
        if (layerStep != null && layerRanges.isEmpty()) out += "--layer-step takes every k-th layer of a --layer-range: give --layer-range A..B with it"
        if (layerStep != null && layerStep < 1) out += "--layer-step must be 1 or more (got $layerStep)"
        for (n in layers) if (n < 1) out += "--layer $n: the first layer is 1"
        for (r in layerRanges) if (r.first < 1 || r.last < r.first) out += "--layer-range ${r.first}..${r.last}: a range runs from a first layer of 1 or more to a layer no lower"
        if (others != null && !anyOnly) out += "--others says what becomes of the lines an --only-... option leaves out: give --only-object, --only-part, --only-tool, or --only-feature with it"
        if (around != null && span == null) out += "--around needs --span (the side of the square drawn round the point, mm)"
        if (span != null && around == null) out += "--span is the side of the square drawn round --around's point: give --around X,Y with it"
        if (span != null && span <= 0) out += "--span must be more than 0 mm (got $span)"
        if (around != null && bedWindow != null) out += "--around and --bed-window both frame the picture: give one"
        bedWindow?.let { w -> if (w[2] <= w[0] || w[3] <= w[1]) out += "--bed-window X0,Y0,X1,Y1 needs X1 above X0 and Y1 above Y0" }
        if (pxPerMm != null && pictureSize != null) out += "--px-per-mm and --picture-size both set the scale: give one"
        if (pxPerMm != null && pxPerMm <= 0) out += "--px-per-mm must be more than 0"
        pictureSize?.let { (w, h) -> if (w < 16 || h < 16) out += "--picture-size ${w}x$h: a picture is at least 16 x 16 pixels" }
        if (layersBelow != null && layersBelow < 0) out += "--layers-below must be 0 or more (got $layersBelow)"
        val others = "(only ${listOfNotNull("cuts".takeIf { anyCut }, "--outside".takeIf { outside.isNotEmpty() }, "--3d".takeIf { threeD }).joinToString(" and ")})"
        if (layersBelow != null && !layerViews) out += "--layers-below adds the layers under a layer picture, and the run draws none $others: give --layer, --layer-at-z, or --layer-range with it"
        if (show.isNotEmpty() && !layerViews) out += "--show marks a layer picture, and the run draws none $others: give --layer, --layer-at-z, or --layer-range with it"
        if (sameFrame && !layerViews) out += "--same-frame frames the run's layer pictures as one, and it draws none $others: give --layer, --layer-at-z, or --layer-range with it"
        if (cutDepth != null && !anyCut) out += "--cut-depth draws the lines along a cut's plane, and the run draws no cut: give --cut-z, --cut-y, --cut-x, --cut-line, or --cut-plane with it"
        if (cutDepth != null && cutDepth <= 0) out += "--cut-depth must be more than 0 mm (got $cutDepth)"
        if (zRange != null && !sideCuts && !anySolid) out += "--z-range limits the heights a cut, the outside, or the 3D view draws, and the run draws none of them (layer pictures and --cut-z are layers' heights already): give --cut-y, --cut-x, --cut-line, --cut-plane, --outside, or --3d with it"
        zRange?.let { if (it.endInclusive <= it.start) out += "--z-range Z0..Z1 needs Z1 above Z0" }
        if (heightStretch != null && !sideViews) out += "--height-stretch stretches the heights of a picture seen from the side, and the run draws none (layer pictures, --cut-z, --outside top, and --3d have none to stretch): give --cut-y, --cut-x, --cut-line, --cut-plane, or --outside front, back, left, or right with it"
        if (threeDFrom.isNotEmpty() && !threeD) out += "--3d-from sets where --3d is seen from: give --3d with it"
        if (heightStretch != null && heightStretch <= 0) out += "--height-stretch must be more than 0 (1 is true proportions)"
        for (l in cutLine) if (Math.hypot(l[2] - l[0], l[3] - l[1]) < 1e-9) out += "--cut-line ${l.joinToString(",")}: its two points are one; a line needs two"
        for ((_, n) in cutPlane) if (n.all { Math.abs(it) < 1e-12 }) out += "--cut-plane: its direction is (0, 0, 0), which faces nowhere"
        sheet?.let { s ->
            val ext = s.fileName.toString().substringAfterLast('.', "").lowercase()
            val other = Format.entries.firstOrNull { it.ext == ext && it != format }
            if (other != null) out += "--sheet $s is a ${other.word.uppercase()} name, but --format is ${format.word}: name it .${format.ext} or give --format ${other.word}"
        }
        return out
    }
}
