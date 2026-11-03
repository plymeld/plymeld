package plymeld.geom

/**
 * A closed polygon on the integer grid, stored flat: `xy = [x0, y0, x1, y1, ...]`.
 * The last vertex connects back to the first (no repeated closing vertex).
 *
 * Orientation convention throughout plymeld: outer boundaries are counter-clockwise
 * (positive signed area), holes are clockwise (negative).
 */
class Polygon(val xy: LongArray) {
    init {
        require(xy.size % 2 == 0) { "xy must hold pairs" }
    }

    val size: Int get() = xy.size / 2
    fun x(i: Int): Long = xy[2 * i]
    fun y(i: Int): Long = xy[2 * i + 1]
    fun point(i: Int) = IntPoint(x(i), y(i))

    /** Twice the signed area (shoelace), exact in Long for coordinates below ~2^31. */
    fun signedArea2(): Long {
        var a = 0L
        val n = size
        if (n < 3) return 0
        var px = x(n - 1); var py = y(n - 1)
        for (i in 0 until n) {
            val cx = x(i); val cy = y(i)
            a += px * cy - cx * py
            px = cx; py = cy
        }
        return a
    }

    /** Signed area in grid units². */
    fun signedArea(): Double = signedArea2() / 2.0

    /** Signed area in mm². */
    fun signedAreaMm2(): Double = signedArea() / (Units.PER_MM.toDouble() * Units.PER_MM)

    fun perimeter(): Double {
        var p = 0.0
        val n = size
        if (n < 2) return 0.0
        var px = x(n - 1); var py = y(n - 1)
        for (i in 0 until n) {
            val dx = (x(i) - px).toDouble(); val dy = (y(i) - py).toDouble()
            p += Math.sqrt(dx * dx + dy * dy)
            px = x(i); py = y(i)
        }
        return p
    }

    fun perimeterMm(): Double = perimeter() / Units.PER_MM

    fun isCcw(): Boolean = signedArea2() > 0

    fun reversed(): Polygon {
        val n = size
        val out = LongArray(xy.size)
        for (i in 0 until n) {
            out[2 * i] = x(n - 1 - i); out[2 * i + 1] = y(n - 1 - i)
        }
        return Polygon(out)
    }

    /** Drops repeated vertices and vertices collinear with their neighbors (exact test). */
    fun simplified(): Polygon {
        val n = size
        if (n < 3) return this
        val keep = BooleanArray(n) { true }
        // repeated points
        for (i in 0 until n) {
            val j = (i + 1) % n
            if (x(i) == x(j) && y(i) == y(j)) keep[j] = false
        }
        // collinear points, on the sequence of kept vertices
        val idx = (0 until n).filter { keep[it] }
        if (idx.size < 3) return Polygon(LongArray(0))
        val keep2 = BooleanArray(idx.size) { true }
        for (k in idx.indices) {
            val a = idx[(k - 1 + idx.size) % idx.size]; val b = idx[k]; val c = idx[(k + 1) % idx.size]
            if (orientation(point(a), point(b), point(c)) == 0) keep2[k] = false
        }
        val out = ArrayList<Long>(idx.size * 2)
        for (k in idx.indices) if (keep2[k]) { out.add(x(idx[k])); out.add(y(idx[k])) }
        return if (out.size == xy.size) this else Polygon(out.toLongArray())
    }

    /**
     * Douglas-Peucker simplification of the closed ring: drops vertices that deviate less than
     * `tolUm` from the simplified outline. Anchored at the two mutually farthest-apart vertices
     * (the first and the vertex farthest from it) so the result does not depend on the seam.
     */
    fun douglasPeucker(tolUm: Long): Polygon {
        val n = size
        if (n <= 4 || tolUm <= 0) return this
        var far = 0; var best = -1L
        for (i in 1 until n) { val dx = x(i) - x(0); val dy = y(i) - y(0); val d = dx * dx + dy * dy; if (d > best) { best = d; far = i } }
        val keep = BooleanArray(n)
        keep[0] = true; keep[far] = true
        val tol2 = tolUm.toDouble() * tolUm
        fun rec(a: Int, b: Int) {   // indices along the ring, a < b (b may wrap as b % n)
            if (b - a < 2) return
            val ax = x(a % n).toDouble(); val ay = y(a % n).toDouble(); val bx = x(b % n).toDouble(); val by = y(b % n).toDouble()
            val vx = bx - ax; val vy = by - ay; val l2 = vx * vx + vy * vy
            var idx = -1; var md = -1.0
            for (k in a + 1 until b) {
                val px = x(k % n) - ax; val py = y(k % n) - ay
                val d = if (l2 == 0.0) px * px + py * py else { val c = px * vy - py * vx; c * c / l2 }
                if (d > md) { md = d; idx = k }
            }
            if (md > tol2) { keep[idx % n] = true; rec(a, idx); rec(idx, b) }
        }
        rec(0, far); rec(far, n)
        val out = ArrayList<Long>()
        for (i in 0 until n) if (keep[i]) { out.add(x(i)); out.add(y(i)) }
        return if (out.size < 6) this else Polygon(out.toLongArray())
    }

    /** Readable and pasteable: `Polygon(x0,y0, x1,y1, ...)` in grid units. */
    override fun toString(): String = (0 until size).joinToString(", ", "Polygon(", ")") { "${x(it)},${y(it)}" }

    fun bounds(): LongArray {
        var minX = Long.MAX_VALUE; var minY = Long.MAX_VALUE; var maxX = Long.MIN_VALUE; var maxY = Long.MIN_VALUE
        for (i in 0 until size) {
            val x = x(i); val y = y(i)
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        return longArrayOf(minX, minY, maxX, maxY)
    }
}
