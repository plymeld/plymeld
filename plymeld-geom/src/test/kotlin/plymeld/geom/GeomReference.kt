package plymeld.geom

/**
 * Frozen reference answers for the kernel (the `.reference.txt` files in `src/test/resources/geom/`): random simple polygons and the
 * areas an independent geometry library (GEOS) gives for their booleans and round-join offsets, computed once outside
 * plymeld and committed. Coordinates are integer micrometres, so plymeld reads the inputs exactly.
 */
object GeomReference {
    class BooleanCase(val op: Booleans.Op, val a: Polygon, val b: Polygon, val areaMm2: Double)
    class OffsetCase(val deltaUm: Long, val polygon: Polygon, val areaMm2: Double)

    private fun lines(name: String): List<List<String>> =
        (GeomReference::class.java.getResourceAsStream("/geom/$name") ?: error("missing test resource /geom/$name"))
            .bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }

    private fun polygon(text: String): Polygon =
        Polygon(text.trim().split(' ').flatMap { it.split(',') }.map { it.toLong() }.toLongArray())

    fun booleans(): List<BooleanCase> = lines("booleans.reference.txt").map { f ->
        val op = when (f[0]) {
            "union" -> Booleans.Op.UNION; "intersection" -> Booleans.Op.INTERSECTION
            "difference" -> Booleans.Op.DIFFERENCE; "xor" -> Booleans.Op.XOR
            else -> error("unknown op '${f[0]}'")
        }
        BooleanCase(op, polygon(f[1]), polygon(f[2]), f[3].toDouble())
    }

    fun offsets(): List<OffsetCase> = lines("offsets.reference.txt").map { f -> OffsetCase(f[0].toLong(), polygon(f[1]), f[2].toDouble()) }
}
