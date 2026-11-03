package plymeld.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import plymeld.mesh.StlReader
import plymeld.mesh.TriangleSoup
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.withLock
import kotlin.math.pow

/** The last frame's costs (ms) and the process's memory (MB); null where not known. */
data class Stats(
    val drawMs: Double = 0.0, val readMs: Double = 0.0, val toImageMs: Double = 0.0, val fps: Double = 0.0,
    val heapMb: Double = 0.0, val rssMb: Double? = null, val firstFrameMs: Long? = null, val loadMs: Long? = null,
)

/**
 * The 3D view's state, shared by the window and the frame thread. Every change asks for a frame ([requestFrame]); the
 * frame thread draws only the newest request, so a fast drag never queues frames behind it.
 */
class ViewerState(val vp: Viewport3D) {
    val camera = Camera()
    var image by mutableStateOf<ImageBitmap?>(null); private set
    var stats by mutableStateOf(Stats()); private set
    var orthographic by mutableStateOf(false); private set
    var modelName by mutableStateOf<String?>(null); private set
    var modelTriangles by mutableStateOf(0); private set
    var pathsName by mutableStateOf<String?>(null); private set
    var paths by mutableStateOf<Toolpaths?>(null); private set
    var layerLo by mutableStateOf(0); private set
    var layerHi by mutableStateOf(0); private set
    var meshAlpha by mutableStateOf(1f); private set
    var busy by mutableStateOf<String?>(null); private set
    var colorBy by mutableStateOf(ColorBy.FEATURE); private set
    var legend by mutableStateOf<Legend?>(null); private set
    /** Feature indices left out of the view. */
    var hiddenFeatures by mutableStateOf(emptySet<Int>()); private set
    var showTravels by mutableStateOf(false); private set
    /** The toolpaths drawn at all (the window's Model tab hides them, its Layers tab shows them). */
    var showPaths by mutableStateOf(true); private set
    /** The move slider: how many of the top layer's segments are drawn; null = all of them. */
    var topMoves by mutableStateOf<Int?>(null); private set
    /** One layer at a time (the layer range's top). */
    var singleLayer by mutableStateOf(false); private set
    /** The camera the shown frame was drawn with (overlays line up with the picture, not with a camera moved since). */
    var frameCamera by mutableStateOf(camera.copy()); private set
    /** The view's size in pixels. */
    var widthPx = 0; private set
    var heightPx = 0; private set
    /** Frames drawn since start, and when the frame thread finished the last one (for screenshots and benchmarks). */
    @Volatile var framesDone = 0; private set

    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val changed = lock.newCondition()
    private var wanted = 0L
    private var drawn = 0L
    private val fpsTimes = ArrayDeque<Long>()

    init {
        Thread({ frameLoop() }, "plymeld-frames").apply { isDaemon = true; start() }
    }

    val aspect: Double get() = if (heightPx > 0) widthPx.toDouble() / heightPx else 1.0

    fun resize(w: Int, h: Int) { if (w != widthPx || h != heightPx) { widthPx = w; heightPx = h; requestFrame() } }

    fun requestFrame() { lock.withLock { wanted++; changed.signalAll() } }

    // ---- controls ----------------------------------------------------------------------------------------------------

    fun orbit(d: Offset) { lock.withLock { camera.orbit(d.x.toDouble(), d.y.toDouble()) }; requestFrame() }
    fun pan(d: Offset) { lock.withLock { camera.pan(d.x.toDouble(), d.y.toDouble(), heightPx.toDouble().coerceAtLeast(1.0)) }; requestFrame() }
    /** Zoom by wheel [notches] (positive = away) towards the cursor at [at] (pixels in the view). */
    fun zoom(notches: Float, at: Offset) {
        if (widthPx == 0 || heightPx == 0) return
        val ndcX = at.x / widthPx * 2.0 - 1; val ndcY = 1 - at.y / heightPx * 2.0
        lock.withLock { camera.zoomAt(ZOOM_STEP.pow(notches.toDouble()), ndcX, ndcY, aspect) }
        requestFrame()
    }
    fun preset(v: Camera.View) { lock.withLock { camera.preset(v) }; requestFrame() }
    fun fit() { val b = vp.gl { bounds() }; lock.withLock { camera.fit(b, aspect) }; requestFrame() }
    /**
     * Every control's state change goes through a snapshot, from whichever thread: the panel then redraws with it. A plain
     * write from the screenshot's thread reached the picture (the frame thread reads the state) but not the legend, which
     * showed hidden walls as ticked.
     */
    private inline fun edit(block: () -> Unit) { Snapshot.withMutableSnapshot(block); requestFrame() }

    fun useOrthographic(on: Boolean) { lock.withLock { camera.orthographic = on }; edit { orthographic = on } }
    fun setLayers(lo: Int, hi: Int) = edit {
        val n = paths?.layers ?: 1
        val h = hi.coerceIn(0, n - 1); val l = if (singleLayer) h else lo.coerceIn(0, h)
        if (h != layerHi) topMoves = null   // a new top layer shows whole, as the move slider resets in other slicers
        layerLo = l; layerHi = h
    }
    fun showMeshAt(a: Float) = edit { meshAlpha = a }
    fun useSingleLayer(on: Boolean) { edit { singleLayer = on }; setLayers(if (on) layerHi else 0, layerHi) }
    fun useTravels(on: Boolean) = edit { showTravels = on }
    fun usePaths(on: Boolean) = edit { showPaths = on }
    fun toggleFeature(i: Int) = edit { hiddenFeatures = if (i in hiddenFeatures) hiddenFeatures - i else hiddenFeatures + i }

    /** The top layer's segment count, for the move slider. */
    val topLayerMoves: Int get() = paths?.let { it.layerStart[layerHi + 1] - it.layerStart[layerHi] } ?: 0
    fun moveTo(k: Int?) = edit { topMoves = k?.coerceIn(0, topLayerMoves)?.takeIf { it < topLayerMoves } }
    /** Step the layer range's top by [d] layers (and the bottom with it in single-layer view). */
    fun stepLayer(d: Int) { val h = (layerHi + d).coerceIn(0, (paths?.layers ?: 1) - 1); setLayers(if (singleLayer) h else minOf(layerLo, h), h) }
    /** Step the move slider by [d] segments. */
    fun stepMove(d: Int) = moveTo((topMoves ?: topLayerMoves) + d)

    /** The last segment drawn: the move slider's (where the nozzle is), or the top layer's last. */
    fun currentSegment(): Int? {
        val p = paths ?: return null
        val end = p.layerStart[layerHi] + (topMoves ?: topLayerMoves)
        return (end - 1).takeIf { it >= p.layerStart[layerHi] }
    }

    /** Colors given from outside (the window: each line in its tool's filament color), with their legend. */
    fun useColors(rgb: IntArray, lg: Legend) {
        vp.gl { setColors(rgb) }
        Snapshot.withMutableSnapshot { colorBy = lg.by; legend = lg }
        requestFrame()
    }

    fun useColorBy(by: ColorBy) {
        val p = paths ?: run { colorBy = by; return }
        val (rgb, lg) = Coloring.colors(p, by)
        vp.gl { setColors(rgb) }
        Snapshot.withMutableSnapshot { colorBy = by; legend = lg }
        requestFrame()
    }

    // ---- loading -----------------------------------------------------------------------------------------------------

    fun loadModel(path: Path) = load("model") {
        val soup: TriangleSoup = StlReader.read(path)
        vp.gl { setMesh(soup) }
        Snapshot.withMutableSnapshot { modelName = path.fileName.toString(); modelTriangles = soup.count; if (paths != null) meshAlpha = 0.25f }
    }

    /** A model already read (the window's job): its triangles, each part in its tool's color ([rgb] per triangle, or none). */
    fun setMeshNow(soup: TriangleSoup, rgb: IntArray?, name: String) = load("model") {
        vp.gl { setMesh(soup, rgb) }
        Snapshot.withMutableSnapshot { modelName = name; modelTriangles = soup.count; if (paths != null) meshAlpha = 0.25f }
    }

    fun loadGcode(path: Path) = load("g-code") {
        val p = GcodeToolpaths.read(path)
        setPathsNow(p, path.fileName.toString())
    }

    fun setPathsNow(p: Toolpaths, name: String) {
        val (rgb, lg) = Coloring.colors(p, colorBy)
        vp.gl { setPaths(p, rgb) }
        Snapshot.withMutableSnapshot {
            paths = p; pathsName = name; layerLo = 0; layerHi = p.layers - 1; legend = lg; hiddenFeatures = emptySet(); topMoves = null
            if (modelName != null) meshAlpha = 0.25f
        }
    }

    private fun load(what: String, body: () -> Unit) {
        Snapshot.withMutableSnapshot { busy = "loading $what" }
        val t = System.nanoTime()
        try { body() } finally {
            val ms = (System.nanoTime() - t) / 1_000_000
            Snapshot.withMutableSnapshot { busy = null; stats = stats.copy(loadMs = ms) }
        }
        fit()
    }

    // ---- frames ------------------------------------------------------------------------------------------------------

    private fun frameLoop() {
        while (true) {
            val cam: Camera; val look: Look; val w: Int; val h: Int; val ticket: Long
            lock.withLock {
                while (wanted == drawn || widthPx <= 0 || heightPx <= 0) changed.await()
                ticket = wanted; cam = camera.copy(); w = widthPx; h = heightPx
                val hidden = IntArray(4); for (i in hiddenFeatures) hidden[i shr 5] = hidden[i shr 5] or (1 shl (i and 31))
                look = Look(meshAlpha = if (modelName == null) 0f else meshAlpha, layerLo = layerLo, layerHi = layerHi, showPaths = showPaths,
                    topMoves = topMoves, hidden = hidden, travels = showTravels)
            }
            val f = vp.gl { frame(w, h, cam, look) }
            val t0 = System.nanoTime()
            val bmp = Image.makeRaster(ImageInfo(f.w, f.h, ColorType.RGBA_8888, ColorAlphaType.OPAQUE), f.rgba, f.w * 4).toComposeImageBitmap()
            val toImageMs = (System.nanoTime() - t0) / 1e6
            val now = System.nanoTime()
            fpsTimes.addLast(now); while (fpsTimes.isNotEmpty() && now - fpsTimes.first() > 1_000_000_000L) fpsTimes.removeFirst()
            val rt = Runtime.getRuntime()
            Snapshot.withMutableSnapshot {
                image = bmp; frameCamera = cam
                stats = stats.copy(drawMs = f.drawMs, readMs = f.readMs, toImageMs = toImageMs, fps = fpsTimes.size.toDouble(),
                    heapMb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0, rssMb = rssMb(),
                    firstFrameMs = stats.firstFrameMs ?: (System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime))
            }
            lock.withLock { drawn = ticket; framesDone++; changed.signalAll() }
        }
    }

    /** Waits until every frame asked for so far is drawn (screenshots, benchmarks). */
    fun awaitFrames(timeoutMs: Long = 60_000) {
        val end = System.currentTimeMillis() + timeoutMs
        lock.withLock { while (drawn != wanted || widthPx == 0) { val left = end - System.currentTimeMillis(); check(left > 0) { "no frame within $timeoutMs ms" }; changed.await(left, java.util.concurrent.TimeUnit.MILLISECONDS) } }
    }

    companion object {
        /** One wheel notch zooms by this factor. plymeld's choice. */
        const val ZOOM_STEP = 1.15
        /** The process's resident memory (MB), from /proc on Linux; null elsewhere. */
        fun rssMb(): Double? = try {
            Files.readAllLines(Path.of("/proc/self/status")).firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))?.get(1)?.toDouble()?.div(1024.0)
        } catch (_: Exception) { null }
    }
}
