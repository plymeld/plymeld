package plymeld.ui

import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * The 3D view's speed (`plymeld-ui --bench`): toolpaths uploaded once, then the view orbited a full
 * turn in [frames] steps at [w] x [h], every frame drawn, read back, and made a Compose image; the costs reported as
 * median and 95th percentile, with memory. A real g-code file, or [Synthetic] toolpaths of a chosen size.
 */
object Bench {
    fun run(vp: Viewport3D, p: Toolpaths, label: String, w: Int, h: Int, frames: Int, png: Path?) {
        val f = Locale.ROOT
        val tUp = System.nanoTime()
        vp.gl { setPaths(p) }
        val uploadMs = (System.nanoTime() - tUp) / 1e6
        val cam = Camera(); cam.preset(Camera.View.ISO); cam.fit(vp.gl { bounds() }, w.toDouble() / h)
        val look = Look(meshAlpha = 0f)
        repeat(3) { vp.gl { frame(w, h, cam, look) } }   // warm-up: shaders, first allocation
        val draw = DoubleArray(frames); val read = DoubleArray(frames); val img = DoubleArray(frames); val total = DoubleArray(frames)
        var last: Frame? = null
        for (i in 0 until frames) {
            cam.yawDeg += 360.0 / frames
            val t0 = System.nanoTime()
            val fr = vp.gl { frame(w, h, cam, look) }
            val t1 = System.nanoTime()
            Image.makeRaster(ImageInfo(fr.w, fr.h, ColorType.RGBA_8888, ColorAlphaType.OPAQUE), fr.rgba, fr.w * 4).toComposeImageBitmap()
            val t2 = System.nanoTime()
            draw[i] = fr.drawMs; read[i] = fr.readMs; img[i] = (t2 - t1) / 1e6; total[i] = (t2 - t0) / 1e6
            last = fr
        }
        System.gc()
        val rt = Runtime.getRuntime()
        fun med(a: DoubleArray) = a.sorted()[a.size / 2]
        fun p95(a: DoubleArray) = a.sorted()[minOf(a.size - 1, (a.size * 0.95).toInt())]
        println("bench: $label")
        println("  OpenGL: ${vp.describe}")
        println("  segments: %,d in %d layers; uploaded in %.0f ms".format(f, p.segments, p.layers, uploadMs))
        println("  view: %d x %d, %d frames orbiting a full turn".format(f, w, h, frames))
        println("  per frame (ms)      median     p95")
        println("    draw            %8.1f %8.1f".format(f, med(draw), p95(draw)))
        println("    read back       %8.1f %8.1f".format(f, med(read), p95(read)))
        println("    to image        %8.1f %8.1f".format(f, med(img), p95(img)))
        println("    total           %8.1f %8.1f   (= %.1f frames/s at the median)".format(f, med(total), p95(total), 1000 / med(total)))
        println("  memory: heap %.0f MB after GC, resident %s".format(f, (rt.totalMemory() - rt.freeMemory()) / 1048576.0,
            ViewerState.rssMb()?.let { "%.0f MB".format(f, it) } ?: "not known on this OS"))
        if (png != null && last != null) {
            val im = Image.makeRaster(ImageInfo(last.w, last.h, ColorType.RGBA_8888, ColorAlphaType.OPAQUE), last.rgba, last.w * 4)
            Files.createDirectories(png.toAbsolutePath().parent)
            Files.write(png, im.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("  last frame: $png")
        }
    }
}

/**
 * Made-up toolpaths of about [segments] segments for measuring: a 60 mm cylinder of rings, 0.2 mm layers, each ring 360
 * segments; the outer ring an outer wall, the next two inner walls, the rest sparse infill. Not a print of anything.
 */
object Synthetic {
    fun toolpaths(segments: Int, layers: Int = 250): Toolpaths {
        val ringsPerLayer = maxOf(1, ceil(segments.toDouble() / layers / RING).toInt())
        val n = layers * ringsPerLayer * RING
        val xyz = FloatArray(n * 6); val feat = ByteArray(n); val starts = IntArray(layers + 1)
        var s = 0
        for (l in 0 until layers) {
            starts[l] = s
            val z = (0.2 * (l + 1)).toFloat()
            for (k in 0 until ringsPerLayer) {
                val r = 30.0 - k * (28.0 / maxOf(1, ringsPerLayer - 1))
                val fi: Byte = when (k) { 0 -> 0; 1, 2 -> 1; else -> 2 }
                for (i in 0 until RING) {
                    val a0 = 2 * PI * i / RING; val a1 = 2 * PI * (i + 1) / RING
                    val o = s * 6
                    xyz[o] = (r * cos(a0)).toFloat(); xyz[o + 1] = (r * sin(a0)).toFloat(); xyz[o + 2] = z
                    xyz[o + 3] = (r * cos(a1)).toFloat(); xyz[o + 4] = (r * sin(a1)).toFloat(); xyz[o + 5] = z
                    feat[s] = fi; s++
                }
            }
        }
        starts[layers] = s
        return Toolpaths(xyz, feat, starts, listOf("Outer wall", "Inner wall", "Sparse infill"))
    }
    private const val RING = 360
}
