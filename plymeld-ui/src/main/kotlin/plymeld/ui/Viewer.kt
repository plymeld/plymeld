package plymeld.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.dropFiles(onDrop: ((List<String>) -> Unit)?): Modifier {
    if (onDrop == null) return this
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val d = event.dragData()
                if (d !is DragData.FilesList) return false
                onDrop(d.readFiles()); return true
            }
        }
    }
    return this.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)
}

/** The 3D view: the latest frame, and the mouse. */
@Composable
internal fun ViewArea(s: ViewerState, modifier: Modifier, onTap: ((Offset, androidx.compose.ui.unit.IntSize) -> Unit)? = null) {
    Box(modifier.onSizeChanged { s.resize(it.width, it.height) }.viewGestures(s, onTap)) {
        s.image?.let { img -> Canvas(Modifier.fillMaxSize()) { drawImage(img) } }
    }
}

/**
 * The view's mouse: a drag turns it (primary button) or moves it (other buttons, or Shift), the wheel zooms; a press and
 * release that did not move past the touch slop is a tap, given to [onTap] with the area's size. Anything drawn over the
 * view (the Plan tab's overlay) takes this too, so the view still turns and zooms under it: Compose gives a pointer event
 * to the topmost of overlapping siblings only (a full-size overlay with its own tap detector would take them all).
 */
internal fun Modifier.viewGestures(s: ViewerState, onTap: ((Offset, androidx.compose.ui.unit.IntSize) -> Unit)? = null): Modifier = pointerInput(s, onTap) {
    awaitPointerEventScope {
        var last: Offset? = null
        var downAt: Offset? = null
        var moved = false
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.first()
            when (ev.type) {
                PointerEventType.Press -> { last = ch.position; if (downAt == null) { downAt = ch.position; moved = false } }
                PointerEventType.Release -> if (!ev.buttons.areAnyPressed) {
                    if (downAt != null && !moved) onTap?.invoke(ch.position, size)
                    last = null; downAt = null
                }
                PointerEventType.Move -> {
                    val l = last
                    if (l != null && ev.buttons.areAnyPressed) {
                        downAt?.let { if ((ch.position - it).getDistance() > viewConfiguration.touchSlop) moved = true }
                        val d = ch.position - l
                        if (ev.buttons.isPrimaryPressed && !ev.keyboardModifiers.isShiftPressed) s.orbit(d) else s.pan(d)
                        last = ch.position
                    }
                }
                PointerEventType.Scroll -> s.zoom(ch.scrollDelta.y, ch.position)
                else -> {}
            }
        }
    }
}

/**
 * A cube showing which way the view faces: its visible faces named, clicking one turns the view to it. Drawn from the
 * camera's own axes, so it always matches the picture.
 */
@Composable
internal fun ViewCube(s: ViewerState, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val cam = s.frameCamera
    val faces = listOf(
        Triple(doubleArrayOf(1.0, 0.0, 0.0), Camera.View.RIGHT, "Right"), Triple(doubleArrayOf(-1.0, 0.0, 0.0), Camera.View.LEFT, "Left"),
        Triple(doubleArrayOf(0.0, 1.0, 0.0), Camera.View.BACK, "Back"), Triple(doubleArrayOf(0.0, -1.0, 0.0), Camera.View.FRONT, "Front"),
        Triple(doubleArrayOf(0.0, 0.0, 1.0), Camera.View.TOP, "Top"), Triple(doubleArrayOf(0.0, 0.0, -1.0), Camera.View.BOTTOM, "Bottom"),
    )
    fun corners(n: DoubleArray): List<DoubleArray> {
        // the face's four corners on the unit cube, in order round it
        val a = if (n[0] != 0.0) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)
        val b = Camera.cross(n, a)
        return listOf(1.0 to 1.0, -1.0 to 1.0, -1.0 to -1.0, 1.0 to -1.0).map { (p, q) -> DoubleArray(3) { n[it] + a[it] * p + b[it] * q } }
    }
    fun screen(p: DoubleArray, size: Float): Offset {
        val r = cam.right(); val u = cam.up(); val k = size * 0.28f
        return Offset(size / 2 + (Camera.dot(p, r) * k).toFloat(), size / 2 - (Camera.dot(p, u) * k).toFloat())
    }
    fun visible() = faces.filter { Camera.dot(it.first, cam.back()) > 1e-6 }
    Canvas(modifier.pointerInput(cam) {
        detectTapGestures { pos ->
            val sz = size.width.toFloat()
            visible().firstOrNull { f -> inside(pos, corners(f.first).map { screen(it, sz) }) }?.let { s.preset(it.second) }
        }
    }) {
        val sz = size.width
        for (f in visible().sortedBy { Camera.dot(it.first, cam.back()) }) {
            val pts = corners(f.first).map { screen(it, sz) }
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (p in pts.drop(1)) lineTo(p.x, p.y); close() }
            val lit = Camera.dot(f.first, cam.back()).toFloat()
            drawPath(path, Color(0.86f + 0.1f * lit, 0.88f + 0.1f * lit, 0.92f + 0.07f * lit, 0.95f))
            drawPath(path, Color(0xFF7A8496), style = Stroke(1.2f))
            if (lit > 0.25f) {
                val center = screen(f.first, sz)
                val t = measurer.measure(f.third, TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color(0xFF2E3440)))
                drawText(t, topLeft = Offset(center.x - t.size.width / 2f, center.y - t.size.height / 2f))
            }
        }
    }
}

private fun inside(p: Offset, poly: List<Offset>): Boolean {
    var c = false; var j = poly.size - 1
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
        j = i
    }
    return c
}
