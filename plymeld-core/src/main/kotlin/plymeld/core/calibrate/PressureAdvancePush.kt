package plymeld.core.calibrate

/**
 * How fast pressure advance runs the extruder at a speed change: the push a pressure-advance plate holds within the
 * extruder's highest filament speed (hardware.tool.<key>.extrude.speed.max), with the plate's margin.
 *
 * The firmware runs the extruder ahead of the nominal flow by the set value K times the head's speed, so at a speed change
 * of dv the filament is pushed K x dv further, scaled by the line's plastic per mm over the filament's cross-section. Klipper
 * smooths that position over a window of its pressure_advance_smooth_time: a weighted average centred on each moment, the
 * weight falling linearly from the centre to nothing at the window's ends (Klipper, Kinematics, "smoothed pressure
 * advance"). A change made in ta = dv / accel, shorter than the window, is spread over it: the push runs at most
 * K x dv x (2 / T - ta / T²); a change longer than the window pushes at K x accel; with no smoothing, K x accel.
 */
object PressureAdvancePush {
    /** The fastest push (mm/s of head speed) at a change of [dv] mm/s made at [accel] mm/s², [setK] s set, smoothed over [smoothS] s. */
    fun rate(setK: Double, dv: Double, accel: Double, smoothS: Double): Double {
        if (dv <= 0 || setK <= 0) return 0.0
        val ta = dv / accel
        return setK * if (smoothS <= 0 || ta >= smoothS) accel else dv * (2 / smoothS - ta / (smoothS * smoothS))
    }

    /**
     * The extruder's fastest filament speed (mm/s) through a change from [v0] to [v1] mm/s: the line's own at [v1] plus the
     * push ([rate]), both times [ratio], the filament per mm of the line. An upper bound: the push peaks while the head is
     * still reaching [v1].
     */
    fun peak(setK: Double, v0: Double, v1: Double, accel: Double, smoothS: Double, ratio: Double): Double =
        ratio * (v1 + rate(setK, v1 - v0, accel, smoothS))

    /**
     * The highest value whose fastest change, from rest to [v] mm/s at [accel] (a run starting from rest, the largest change
     * a plate's wall or corner makes), keeps the filament within [limit] mm/s: null when the line alone at [v] is over it.
     */
    fun highestK(limit: Double, v: Double, accel: Double, smoothS: Double, ratio: Double): Double? {
        val room = limit / ratio - v
        if (room <= 0) return null
        return room / rate(1.0, v, accel, smoothS)
    }
}
