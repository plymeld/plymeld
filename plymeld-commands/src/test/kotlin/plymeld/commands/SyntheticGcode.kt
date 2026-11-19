package plymeld.commands

import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.Locale

/**
 * Synthetic g-code for the render command's tests: lines at known places, each move's filament worked out here from the
 * bead's cross-section h (w - h (1 - pi / 4)) written out independently of plymeld's own bead code, on 1.75 mm filament.
 */
class SyntheticGcode(val diameter: Double = 1.75, val markers: Boolean = true, val names: Boolean = true) {
    private val b = StringBuilder()
    private var x = Double.NaN; private var y = Double.NaN
    var layer = 0; private set
    var z = 0.0; private set
    private var h = 0.2

    init {
        if (names) b.append("; filament_diameter: ${n(diameter)}\n")
        b.append("G90\nM83\nM104 S210\nM106 S255\n")
    }

    private fun n(v: Double) = String.format(Locale.ROOT, "%.5f", v).trimEnd('0').trimEnd('.')
    fun e(len: Double, w: Double, h: Double = this.h): Double = len * h * (w - h * (1 - Math.PI / 4)) / (Math.PI * diameter * diameter / 4)

    fun layer(top: Double, height: Double): SyntheticGcode {
        layer++; z = top; h = height
        if (markers) b.append(";LAYER_CHANGE\n;Z:${n(top)}\n;HEIGHT:${n(height)}\n")
        b.append("G1 Z${n(top)} F600\n")
        return this
    }
    fun feature(name: String, width: Double): SyntheticGcode { if (names) b.append(";TYPE:$name\n;WIDTH:${n(width)}\n"); return this }
    fun obj(name: String?): SyntheticGcode { b.append(if (name == null) "; object end\n" else "; object start $name\n"); return this }
    fun tool(t: Int): SyntheticGcode { b.append("T$t\n"); return this }
    fun raw(line: String): SyntheticGcode { b.append(line).append('\n'); return this }
    fun travel(tx: Double, ty: Double): SyntheticGcode { b.append("G1 X${n(tx)} Y${n(ty)} F9000\n"); x = tx; y = ty; return this }
    /** A line from where the head is to (tx, ty), [w] mm wide. */
    fun line(tx: Double, ty: Double, w: Double, speed: Double = 50.0): SyntheticGcode {
        b.append("G1 X${n(tx)} Y${n(ty)} E${n(e(Math.hypot(tx - x, ty - y), w))} F${n(speed * 60)}\n"); x = tx; y = ty; return this
    }
    /** An arc from where the head is to (tx, ty) about (cx, cy), G2 clockwise, [w] mm wide. */
    fun arc(tx: Double, ty: Double, cx: Double, cy: Double, cw: Boolean, w: Double): SyntheticGcode {
        val r = Math.hypot(x - cx, y - cy)
        val a0 = Math.atan2(y - cy, x - cx); val a1 = Math.atan2(ty - cy, tx - cx)
        var sweep = if (cw) a0 - a1 else a1 - a0
        while (sweep <= 1e-12) sweep += 2 * Math.PI
        b.append("${if (cw) "G2" else "G3"} X${n(tx)} Y${n(ty)} I${n(cx - x)} J${n(cy - y)} E${n(e(sweep * r, w))} F3000\n"); x = tx; y = ty; return this
    }
    /** The same arc as [n] straight lines. */
    fun arcAsLines(tx: Double, ty: Double, cx: Double, cy: Double, cw: Boolean, w: Double, n: Int): SyntheticGcode {
        val r = Math.hypot(x - cx, y - cy)
        val a0 = Math.atan2(y - cy, x - cx); val a1 = Math.atan2(ty - cy, tx - cx)
        var sweep = if (cw) a0 - a1 else a1 - a0
        while (sweep <= 1e-12) sweep += 2 * Math.PI
        for (k in 1..n) { val a = a0 + (if (cw) -sweep else sweep) * k / n; line(if (k == n) tx else cx + r * Math.cos(a), if (k == n) ty else cy + r * Math.sin(a), w) }
        return this
    }
    fun text(): String = b.toString()
    fun write(p: Path): Path { java.nio.file.Files.writeString(p, b.toString()); return p }

    companion object {
        /** Runs `plymeld render` with [args]: its exit status, standard output and error lines. */
        fun render(vararg args: String): Triple<Int, List<String>, List<String>> {
            val out = ArrayList<String>(); val err = ArrayList<String>()
            val code = Commands.invoke(listOf("render") + args, { out += it }, { err += it })
            return Triple(code, out, err)
        }
        fun png(p: Path): BufferedImage = javax.imageio.ImageIO.read(p.toFile())
        /** The drawing's ground color (an empty spot). */
        const val GROUND = 0xECECEC
        fun rgb(img: BufferedImage, x: Int, y: Int) = img.getRGB(x, y) and 0xFFFFFF
        fun isGround(img: BufferedImage, x: Int, y: Int) = rgb(img, x, y) == GROUND
    }
}
