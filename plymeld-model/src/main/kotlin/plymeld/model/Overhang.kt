package plymeld.model

/**
 * How far a filament holds out over air: a line may hang [share] of its width
 * past the line below it, with the cooling it prints with. The line is the outer wall, [widthMm]
 * wide. So each layer may step out [stepMm] beyond the one below whatever its height, and the
 * flattest face that holds depends on the layer height: a 0.08 mm layer stepping 0.22 mm is a 20°
 * face; a 0.2 mm layer stepping as far is a 42° face.
 */
class Overhang(val share: Double, val widthMm: Double) {
    init { require(share > 0 && widthMm > 0) { "overhang share and line width must be positive (share $share, width $widthMm mm)" } }

    /** How far a layer may step out beyond the one below (mm). */
    val stepMm: Double get() = share * widthMm

    /** The flattest face, in degrees from horizontal, that holds at layers [layerMm] thick. */
    fun angleDeg(layerMm: Double): Double = Math.toDegrees(Math.atan2(layerMm, stepMm))

    /**
     * Whether a face needs support at layers [layerMm] thick. [down]: the cosine of its tilt from
     * horizontal (1 = flat, facing down). It steps layerMm / tan(tilt) per layer; it holds while that
     * is at most [stepMm].
     */
    fun needsSupport(down: Double, layerMm: Double): Boolean =
        down > 0 && stepMm * Math.sqrt(maxOf(0.0, 1 - down * down)) < layerMm * down

    override fun toString() = "a line may hang %.0f %% of its %.2f mm width past the one below".format(java.util.Locale.ROOT, share * 100, widthMm)

    companion object {
        /** The share that makes [deg] the limit at layers [layerMm] thick with lines [widthMm] wide (another slicer's fixed angle, and tests). */
        fun fromAngle(deg: Double, layerMm: Double, widthMm: Double) = Overhang(layerMm / Math.tan(Math.toRadians(deg)) / widthMm, widthMm)
    }
}
