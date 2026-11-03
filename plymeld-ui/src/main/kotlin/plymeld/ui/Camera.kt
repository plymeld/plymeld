package plymeld.ui

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The 3D view's camera, in the printer's frame (mm, Z up): it orbits a [target] point at [distance], [yawDeg] around Z
 * (0 = looking from +X, -90 = from the front, -Y) and [pitchDeg] above the horizon (90 = straight down). The basis comes
 * from the two angles directly, not from a look-at with Z as up, so looking straight down or up has no singularity.
 * Perspective with a vertical field of view [fovYDeg], or orthographic with the same height at the target ([halfHeight]),
 * so switching between them keeps the target's scale.
 */
class Camera(
    var target: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    var distance: Double = 200.0,
    var yawDeg: Double = -60.0,
    var pitchDeg: Double = 30.0,
    var orthographic: Boolean = false,
    val fovYDeg: Double = 30.0,
) {
    fun copy() = Camera(target.copyOf(), distance, yawDeg, pitchDeg, orthographic, fovYDeg)

    /** From the target towards the eye (unit). */
    fun back(): DoubleArray { val y = rad(yawDeg); val p = rad(pitchDeg); return doubleArrayOf(cos(p) * cos(y), cos(p) * sin(y), sin(p)) }
    /** The screen's right (unit, horizontal). */
    fun right(): DoubleArray { val y = rad(yawDeg); return doubleArrayOf(-sin(y), cos(y), 0.0) }
    /** The screen's up (unit): back x right. */
    fun up(): DoubleArray = cross(back(), right())
    fun eye(): DoubleArray { val b = back(); return DoubleArray(3) { target[it] + b[it] * distance } }
    /** Half the view's height at the target's depth (mm), the same in both projections. */
    fun halfHeight(): Double = distance * tan(rad(fovYDeg) / 2)

    // ---- the controls ----------------------------------------------------------------------------------------------

    /** Orbit by a drag of ([dxPx], [dyPx]) pixels: [degPerPx] each way, the pitch kept within straight up and down. */
    fun orbit(dxPx: Double, dyPx: Double, degPerPx: Double = 0.4) {
        yawDeg -= dxPx * degPerPx
        pitchDeg = (pitchDeg + dyPx * degPerPx).coerceIn(-90.0, 90.0)
    }

    /** Pan by a drag of ([dxPx], [dyPx]) pixels in a view [heightPx] tall: the target moves with the cursor. */
    fun pan(dxPx: Double, dyPx: Double, heightPx: Double) {
        val mmPerPx = 2 * halfHeight() / heightPx
        val r = right(); val u = up()
        for (k in 0..2) target[k] += (-dxPx * r[k] + dyPx * u[k]) * mmPerPx
    }

    /**
     * Zoom by [factor] (under 1 = closer) towards the point under the cursor, at ([ndcX], [ndcY]) in -1..1 (y up), in a
     * view of [aspect] width / height: that point stays under the cursor. The point is taken on the plane through the
     * target facing the view; the eye moves along the line to it (perspective) or the view shrinks about it
     * (orthographic), so its direction from the eye, and so its place on the screen, does not change.
     */
    fun zoomAt(factor: Double, ndcX: Double, ndcY: Double, aspect: Double) {
        val h = halfHeight(); val r = right(); val u = up()
        val p = DoubleArray(3) { target[it] + r[it] * ndcX * h * aspect + u[it] * ndcY * h }
        for (k in 0..2) target[k] = p[k] + (target[k] - p[k]) * factor
        distance *= factor
    }

    /** Frame a box ([b]: min x, y, z, max x, y, z) so all of it is in a view of [aspect], from the current direction. */
    fun fit(b: DoubleArray, aspect: Double) {
        target = doubleArrayOf((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2)
        val radius = max(1e-3, sqrt((b[3] - b[0]).sq() + (b[4] - b[1]).sq() + (b[5] - b[2]).sq()) / 2)
        val halfY = rad(fovYDeg) / 2; val halfX = atan(tan(halfY) * aspect)
        val half = min(halfY, halfX)
        // a sphere round the box fills the narrower half-angle; orthographic, the same distance shows at least as much
        distance = radius / sin(half) * FIT_MARGIN
    }

    /** One of the named views. */
    fun preset(v: View) { yawDeg = v.yawDeg; pitchDeg = v.pitchDeg }

    enum class View(val label: String, val yawDeg: Double, val pitchDeg: Double) {
        FRONT("Front", -90.0, 0.0), BACK("Back", 90.0, 0.0), LEFT("Left", 180.0, 0.0), RIGHT("Right", 0.0, 0.0),
        TOP("Top", -90.0, 90.0), BOTTOM("Bottom", -90.0, -90.0),
        /** The front-right corner, from above: the cube's diagonal (35.26 deg). */
        ISO("Iso", -45.0, 35.264389682754654),
    }

    // ---- matrices (column-major, as OpenGL takes them) -----------------------------------------------------------------

    fun view(): FloatArray {
        val r = right(); val u = up(); val b = back(); val e = eye()
        return floatArrayOf(
            r[0].toFloat(), u[0].toFloat(), b[0].toFloat(), 0f,
            r[1].toFloat(), u[1].toFloat(), b[1].toFloat(), 0f,
            r[2].toFloat(), u[2].toFloat(), b[2].toFloat(), 0f,
            (-dot(r, e)).toFloat(), (-dot(u, e)).toFloat(), (-dot(b, e)).toFloat(), 1f)
    }

    /**
     * The projection for a view of [aspect], the scene within [sceneRadius] of the target; [flipY] turns the image upside
     * down (for reading an off-screen frame back top row first).
     */
    fun projection(aspect: Double, sceneRadius: Double, flipY: Boolean = false): FloatArray {
        val far = distance + 2 * sceneRadius + 10.0
        val sy = if (flipY) -1.0 else 1.0
        return if (orthographic) {
            val h = halfHeight(); val w = h * aspect
            val near = -far
            floatArrayOf(
                (1 / w).toFloat(), 0f, 0f, 0f,
                0f, (sy / h).toFloat(), 0f, 0f,
                0f, 0f, (-2 / (far - near)).toFloat(), 0f,
                0f, 0f, (-(far + near) / (far - near)).toFloat(), 1f)
        } else {
            val near = max(distance * 0.01, 0.05)
            val f = 1 / tan(rad(fovYDeg) / 2)
            floatArrayOf(
                (f / aspect).toFloat(), 0f, 0f, 0f,
                0f, (sy * f).toFloat(), 0f, 0f,
                0f, 0f, ((far + near) / (near - far)).toFloat(), -1f,
                0f, 0f, (2 * far * near / (near - far)).toFloat(), 0f)
        }
    }

    /** Where world point [p] lands on the screen, in -1..1 each way (y up), for a view of [aspect]. */
    fun project(p: DoubleArray, aspect: Double): DoubleArray {
        val e = eye(); val d = DoubleArray(3) { p[it] - e[it] }
        val x = dot(d, right()); val y = dot(d, up()); val z = -dot(d, back())
        val h = halfHeight()
        return if (orthographic) doubleArrayOf(x / (h * aspect), y / h)
        else { val s = distance / z; doubleArrayOf(x * s / (h * aspect), y * s / h) }
    }

    companion object {
        /** Room around a fitted box, as a share of its bounding sphere. plymeld's choice. */
        const val FIT_MARGIN = 1.05
        private fun rad(d: Double) = d * PI / 180
        private fun Double.sq() = this * this
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    }
}
