package plymeld.mesh

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class MeshFormatException(message: String) : RuntimeException(message)

/**
 * Streaming STL reader, binary and ASCII.
 *
 * Never loads the file as a whole: binary is parsed in fixed-size blocks, ASCII
 * line by line. Output is a [TriangleSoup] (flat float array). Facet normals are
 * discarded — they are recomputed from winding whenever needed, which is what
 * every slicer does because files lie about them.
 *
 * Format detection does not trust the "solid" prefix: binary files written by
 * some tools start with it. A file is binary iff its length is exactly
 * 84 + 50·n for the n in its header; otherwise it must parse as ASCII.
 */
object StlReader {
    private const val HEADER = 80
    private const val TRI_BYTES = 50

    fun read(path: Path): TriangleSoup {
        val size = Files.size(path)
        Files.newInputStream(path).use { raw ->
            val input = BufferedInputStream(raw, 1 shl 20)
            input.mark(HEADER + 4)
            val head = ByteArray(HEADER + 4)
            val got = readFully(input, head, 0, head.size)
            input.reset()
            if (got == head.size) {
                val n = ByteBuffer.wrap(head, HEADER, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
                if (size == HEADER + 4 + n * TRI_BYTES) return readBinary(input, n.toInt())
            }
            return readAscii(input, path.toString())
        }
    }

    /** Binary body after the caller has positioned the stream at byte 0; `count` from the header. */
    fun readBinary(input: InputStream, count: Int): TriangleSoup {
        val skip = ByteArray(HEADER + 4)
        if (readFully(input, skip, 0, skip.size) != skip.size) throw MeshFormatException("binary STL shorter than its header")
        val coords = FloatArray(count * 9)
        val trisPerBlock = 4096
        val block = ByteArray(trisPerBlock * TRI_BYTES)
        val bb = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        var tri = 0
        while (tri < count) {
            val n = minOf(trisPerBlock, count - tri)
            val want = n * TRI_BYTES
            if (readFully(input, block, 0, want) != want) throw MeshFormatException("binary STL truncated at triangle $tri of $count")
            var off = 0
            var out = tri * 9
            repeat(n) {
                // 12 bytes normal (ignored), 3 × 12 bytes vertices, 2 bytes attribute
                var p = off + 12
                for (k in 0 until 9) {
                    val v = bb.getFloat(p)
                    if (v.isNaN() || v.isInfinite()) throw MeshFormatException("non-finite coordinate in triangle ${tri + it}")
                    coords[out + k] = v
                    p += 4
                }
                off += TRI_BYTES
                out += 9
            }
            tri += n
        }
        return TriangleSoup(coords, count)
    }

    fun readAscii(input: InputStream, name: String = "<stream>"): TriangleSoup {
        val reader = input.bufferedReader(StandardCharsets.US_ASCII)
        var coords = FloatArray(9 * 1024)
        var n = 0          // floats written
        var vertexInFacet = 0
        var sawSolid = false
        var lineNo = 0
        while (true) {
            val raw = reader.readLine() ?: break
            lineNo++
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("vertex") -> {
                    if (n + 3 > coords.size) coords = coords.copyOf(coords.size * 2)
                    var i = 6
                    for (k in 0 until 3) {
                        while (i < line.length && line[i] == ' ') i++
                        var j = i
                        while (j < line.length && line[j] != ' ') j++
                        if (j == i) throw MeshFormatException("$name:$lineNo: expected 3 coordinates")
                        val v = line.substring(i, j).toFloatOrNull()
                            ?: throw MeshFormatException("$name:$lineNo: bad number '${line.substring(i, j)}'")
                        if (v.isNaN() || v.isInfinite()) throw MeshFormatException("$name:$lineNo: non-finite coordinate")
                        coords[n++] = v
                        i = j
                    }
                    vertexInFacet++
                    if (vertexInFacet > 3) throw MeshFormatException("$name:$lineNo: more than 3 vertices in a facet")
                }
                line.startsWith("outer loop") -> vertexInFacet = 0
                line.startsWith("endloop") -> if (vertexInFacet != 3) throw MeshFormatException("$name:$lineNo: facet with $vertexInFacet vertices")
                line.startsWith("solid") -> sawSolid = true
                line.startsWith("facet") || line.startsWith("endfacet") || line.startsWith("endsolid") -> Unit
                else -> throw MeshFormatException("$name:$lineNo: not an STL line: '${line.take(40)}'")
            }
        }
        if (!sawSolid) throw MeshFormatException("$name: neither a binary STL (length mismatch) nor ASCII (no 'solid')")
        if (n % 9 != 0) throw MeshFormatException("$name: ended mid-facet")
        return TriangleSoup(coords.copyOf(n), n / 9)
    }

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var got = 0
        while (got < len) {
            val r = input.read(buf, off + got, len - got)
            if (r < 0) return got
            got += r
        }
        return got
    }
}
