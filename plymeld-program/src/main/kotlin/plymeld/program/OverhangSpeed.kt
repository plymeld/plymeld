package plymeld.program

/**
 * Wall speed over an overhang, GRADED by the share of the bead over air (0..1): the wall's own speed
 * up to [from] (h / 2w), then a straight line down to the bridge speed at share 1 ([graded]).
 *
 * History: this replaced straight lines through four overhang tier speeds (overhang_1_4 ..
 * overhang_4_4_speed), fitted to Flash Studio's output on two fin ladders at 0.08 mm
 * layers. Before that, plymeld stepped straight to the first tier at 0.10 and held each band flat, labeled
 * "Overhang wall": a face leaning ~28 deg (share ~0.10) flipped between 125 and 50 mm/s every few
 * millimetres, 78 m of one model's walls against Flash Studio's 0.4 m.
 */
object OverhangSpeed {
    /**
     * Below this share a wall is not slowed at all: h / 2w (derived). A bead
     * is a stadium - a flat w - h wide, flanks rounded with radius h/2 - and in any vertical wall
     * each bead's rounded flank already hangs past the flank below it. So the first h/2 of outward
     * step changes nothing; beyond it the bead's flat underside starts to reach over air. At 0.08 mm
     * layers and 0.42 mm lines this is 0.095 (Flash Studio slows from 0.10 at those settings); at
     * 0.2 mm layers 0.24. Model-derived: a print of the overhang coupon's fins (test-models/overhang_bridge_coupon.stl) is the test.
     */
    fun from(widthUm: Long, heightUm: Long): Double = heightUm / (2.0 * widthUm)

    /**
     * A wall line's speed at overhang [share] (0 = fully on the line below, 1 = fully over air):
     * its own speed up to [from], then straight down to [bridgeSpeed] at share 1 — a line wholly
     * over air is a bridge strand (replaces four overhang tier speeds). Never faster than the wall's own speed.
     */
    fun graded(share: Double, wallSpeed: Double, bridgeSpeed: Double, from: Double): Double {
        if (share <= from || from >= 1.0) return wallSpeed
        val end = Math.min(wallSpeed, bridgeSpeed)
        val t = ((share - from) / (1.0 - from)).coerceIn(0.0, 1.0)
        return wallSpeed + (end - wallSpeed) * t
    }
}
