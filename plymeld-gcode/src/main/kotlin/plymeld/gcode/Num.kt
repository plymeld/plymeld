package plymeld.gcode

import java.util.Locale

/**
 * Fixed-decimal number text for g-code, byte for byte what `String.format(Locale.ROOT, "%.Nf", v)` gives with the
 * trailing zeros and the point trimmed, without the cost of `String.format` (the writer's largest cost: 255k lines
 * of the Benchy at 1.1 s). `%.Nf` rounds the double's exact decimal value half up; a scaled `Math.round`
 * agrees except within the product's own rounding error of a tie, so a value that close to a tie takes the slow,
 * exact path. NumTest holds the two equal over random and edge values.
 */
object Num {
    private val POW10 = longArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000)
    /** How near a tie (in units of the last digit) counts as a tie for the exact path: well above a double product's error. */
    private const val TIE_BAND = 1e-6

    /** [v] with at most [decimals] decimals, trailing zeros and the point trimmed; a negative value rounding to zero is "0", or "-0" with [minusZero] (as `%.5f` left it for E values). */
    fun trimmed(v: Double, decimals: Int, minusZero: Boolean = false): String {
        if (v.isNaN() || v.isInfinite() || decimals !in 0..7 || Math.abs(v) >= 1e15) return slow(v, decimals, minusZero)
        val scale = POW10[decimals]
        val p = Math.abs(v) * scale
        val fl = Math.floor(p)
        val frac = p - fl
        if (Math.abs(frac - 0.5) < TIE_BAND) return slow(v, decimals, minusZero)
        var n = fl.toLong() + (if (frac > 0.5) 1 else 0)
        val neg = (v < 0 || (v == 0.0 && 1.0 / v < 0)) && (n != 0L || minusZero)   // -0.0 formats as "-0.00000" too
        val sb = StringBuilder(24)
        if (neg) sb.append('-')
        sb.append(n / scale)
        var rem = n % scale
        if (rem != 0L) {
            // the fraction's digits, zero-padded to [decimals], then the trailing zeros dropped
            var d = decimals
            while (rem % 10 == 0L) { rem /= 10; d-- }
            sb.append('.')
            val digits = rem.toString()
            repeat(d - digits.length) { sb.append('0') }
            sb.append(digits)
        }
        return sb.toString()
    }

    /** The reference: what the writer did before. */
    fun slow(v: Double, decimals: Int, minusZero: Boolean = false): String =
        String.format(Locale.ROOT, "%.${decimals}f", v).let { if (decimals > 0) it.trimEnd('0').trimEnd('.') else it }.let { if (it == "-0" && !minusZero) "0" else it }
}
