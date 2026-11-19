package plymeld.program

/**
 * The bead model: a line laid [h] thick and [w] wide has the cross-section of a rectangle with rounded sides (a flat
 * w - h wide, its flanks half circles of diameter h), so its area is h (w - h (1 - pi / 4)), and two beads side by side
 * touch with their centers w - h (1 - pi / 4) apart (each flank's rounding overlaps its neighbor's). Readers of g-code use
 * it to turn the plastic a move lays into the width it lays it at.
 */
object Bead {
    /** The share of a bead's height its flanks' rounding takes off its flat: 1 - pi / 4. */
    const val FLANK = 1 - Math.PI / 4

    /** The cross-section (mm²) of a bead [w] wide and [h] thick (mm). */
    fun area(w: Double, h: Double): Double = h * (w - h * FLANK)

    /** The width (mm) of a bead [h] thick (mm) whose cross-section is [area] (mm²). */
    fun width(area: Double, h: Double): Double = area / h + h * FLANK

    /** How far apart (mm) the centers of two beads [w] wide and [h] thick lie when they touch side by side. */
    fun spacing(w: Double, h: Double): Double = w - h * FLANK
}
