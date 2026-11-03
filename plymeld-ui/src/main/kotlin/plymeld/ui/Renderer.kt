package plymeld.ui

import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.system.MemoryUtil
import plymeld.mesh.TriangleSoup
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.sqrt

/** One picture of the 3D view: [w] x [h] RGBA, top row first, and what it cost (ms). */
class Frame(val w: Int, val h: Int, val rgba: ByteArray, val drawMs: Double, val readMs: Double)

/**
 * What to draw: the model shown at [meshAlpha] (0 = hidden); the toolpaths' layers [layerLo]..[layerHi] (inclusive), the
 * top one only its first [topMoves] segments when set (the move slider); the features whose bits are set in [hidden]
 * left out; travel moves when [travels].
 */
class Look(val meshAlpha: Float = 1f, val layerLo: Int = 0, val layerHi: Int = Int.MAX_VALUE, val showPaths: Boolean = true,
           val topMoves: Int? = null, val hidden: IntArray = IntArray(4), val travels: Boolean = false)

/**
 * The 3D view's own thread: OpenGL wants every call for a context from the thread it is current on, so all of it runs
 * here, one job at a time ([gl]).
 */
class Viewport3D(platform: String? = null) : AutoCloseable {
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "plymeld-gl").apply { isDaemon = true } }
    private lateinit var ctx: GlContext
    private lateinit var r: Renderer
    val describe: String

    init {
        describe = exec.submit(Callable { ctx = GlContext.open(platform); r = Renderer(); ctx.describe }).get()
    }

    fun <T> gl(block: Renderer.() -> T): T = exec.submit(Callable { r.block() }).get()

    override fun close() {
        exec.submit { r.dispose(); ctx.close() }.get()
        exec.shutdown()
    }
}

/**
 * Draws on the GL thread into a framebuffer of its own (4x multisampled, resolved, read back). The model flat-shaded by a
 * light at the eye; toolpaths as lines colored by feature ([Palette]); a 10 mm grid on z = 0 around what is shown;
 * a soft gray gradient behind (a light part still reads against it).
 */
class Renderer {
    private val meshProg = program(MESH_VS, MESH_FS)
    private val lineProg = program(LINE_VS, LINE_FS)
    private val pathProg = program(PATH_VS, PATH_FS, PATH_GS)
    private var pathColVbo = 0
    private var pathIbo = 0
    private var travelVao = 0; private var travelVbo = 0
    private var travelStart = IntArray(1)
    private val bgProg = program(BG_VS, BG_FS)
    private val bgVao = glGenVertexArrays()

    /** A dark background and grid (the window's); false: the light ones. */
    var dark = false
    private var meshVao = 0; private var meshVbo = 0; private var meshVerts = 0
    private var meshBounds: DoubleArray? = null
    private var pathVao = 0; private var pathVbo = 0
    private var layerStart = IntArray(1)
    private var pathBounds: DoubleArray? = null
    private var gridVao = 0; private var gridVbo = 0; private var gridVerts = 0

    private var fbW = 0; private var fbH = 0
    private var msFbo = 0; private var msColor = 0; private var msDepth = 0
    private var outFbo = 0; private var outColor = 0
    private var readBuf: ByteBuffer? = null

    /** What is shown, for fitting the view and sizing the depth range: the model and the toolpaths together. */
    fun bounds(): DoubleArray {
        val bs = listOfNotNull(meshBounds, pathBounds)
        if (bs.isEmpty()) return doubleArrayOf(-50.0, -50.0, 0.0, 50.0, 50.0, 50.0)
        return DoubleArray(6) { k -> if (k < 3) bs.minOf { it[k] } else bs.maxOf { it[k] } }
    }

    /** The model: [soup]'s triangles, each colored [rgb] (0xRRGGBB per triangle: a part's tool color) or [DEFAULT_MESH_RGB]. */
    fun setMesh(soup: TriangleSoup?, rgb: IntArray? = null) {
        if (meshVao != 0) { glDeleteVertexArrays(meshVao); glDeleteBuffers(meshVbo); meshVao = 0 }
        meshBounds = null; meshVerts = 0
        if (soup == null || soup.count == 0) { rebuildGrid(); return }
        require(rgb == null || rgb.size == soup.count) { "one color per triangle: ${rgb?.size} for ${soup.count}" }
        // position + face normal + color per corner, 9 floats
        val buf = MemoryUtil.memAllocFloat(soup.count * 27)
        val c = soup.coords
        for (t in 0 until soup.count) {
            val o = t * 9
            val ux = c[o + 3] - c[o]; val uy = c[o + 4] - c[o + 1]; val uz = c[o + 5] - c[o + 2]
            val vx = c[o + 6] - c[o]; val vy = c[o + 7] - c[o + 1]; val vz = c[o + 8] - c[o + 2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0f } ?: 1f
            nx /= l; ny /= l; nz /= l
            val col = rgb?.get(t) ?: DEFAULT_MESH_RGB
            val cr = ((col shr 16) and 0xFF) / 255f; val cg = ((col shr 8) and 0xFF) / 255f; val cb = (col and 0xFF) / 255f
            for (k in 0..2) { buf.put(c[o + k * 3]).put(c[o + k * 3 + 1]).put(c[o + k * 3 + 2]).put(nx).put(ny).put(nz).put(cr).put(cg).put(cb) }
        }
        buf.flip()
        meshVao = glGenVertexArrays(); glBindVertexArray(meshVao)
        meshVbo = glGenBuffers(); glBindBuffer(GL_ARRAY_BUFFER, meshVbo); glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 36, 0L); glEnableVertexAttribArray(0)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 36, 12L); glEnableVertexAttribArray(1)
        glVertexAttribPointer(2, 3, GL_FLOAT, false, 36, 24L); glEnableVertexAttribArray(2)
        glBindVertexArray(0)
        MemoryUtil.memFree(buf)
        meshVerts = soup.count * 3
        meshBounds = soup.bounds()
        rebuildGrid()
    }

    /**
     * The toolpaths: per end 20 bytes of shape (x, y, z, line width as floats, the feature as an int: 40 per segment) and,
     * in a buffer of their own so a new color scheme re-sends only them, 4 bytes of color ([colors], RGB per segment).
     * Travels: their ends, 24 bytes per move.
     */
    fun setPaths(p: Toolpaths?, colors: IntArray? = null) {
        if (pathVao != 0) { glDeleteVertexArrays(pathVao); glDeleteBuffers(pathVbo); glDeleteBuffers(pathColVbo); glDeleteBuffers(pathIbo); pathVao = 0 }
        if (travelVao != 0) { glDeleteVertexArrays(travelVao); glDeleteBuffers(travelVbo); travelVao = 0 }
        pathBounds = null; layerStart = IntArray(1); travelStart = IntArray(1)
        if (p == null || p.segments == 0) { rebuildGrid(); return }
        val buf = MemoryUtil.memAlloc(p.segments * 48)
        for (s in 0 until p.segments) for (end in 0..1) {
            val o = s * 6 + end * 3
            buf.putFloat(p.xyz[o]).putFloat(p.xyz[o + 1]).putFloat(p.xyz[o + 2]).putFloat(p.width[s]).putFloat(p.height[s]).putInt(p.feature[s].toInt())
        }
        buf.flip()
        pathVao = glGenVertexArrays(); glBindVertexArray(pathVao)
        pathVbo = glGenBuffers(); glBindBuffer(GL_ARRAY_BUFFER, pathVbo); glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0L); glEnableVertexAttribArray(0)
        glVertexAttribPointer(1, 1, GL_FLOAT, false, 24, 12L); glEnableVertexAttribArray(1)
        glVertexAttribPointer(4, 1, GL_FLOAT, false, 24, 16L); glEnableVertexAttribArray(4)
        glVertexAttribIPointer(2, 1, GL_UNSIGNED_INT, 24, 20L); glEnableVertexAttribArray(2)
        MemoryUtil.memFree(buf)
        pathColVbo = glGenBuffers(); glBindBuffer(GL_ARRAY_BUFFER, pathColVbo)
        glBufferData(GL_ARRAY_BUFFER, p.segments * 8L, GL_DYNAMIC_DRAW)
        glVertexAttribPointer(3, 4, GL_UNSIGNED_BYTE, true, 4, 0L); glEnableVertexAttribArray(3)
        // each segment with its neighbors (lines with adjacency: the previous segment's start, its own two ends, the next
        // one's end), so the shader can mitre the joints; a segment whose neighbor does not touch it names itself
        val idx = MemoryUtil.memAllocInt(p.segments * 4)
        fun touches(endOf: Int, startOf: Int) = (0..2).all { p.xyz[endOf * 6 + 3 + it] == p.xyz[startOf * 6 + it] }
        for (s in 0 until p.segments) {
            val prev = if (s > 0 && touches(s - 1, s)) 2 * (s - 1) else 2 * s
            val next = if (s + 1 < p.segments && touches(s, s + 1)) 2 * (s + 1) + 1 else 2 * s + 1
            idx.put(prev).put(2 * s).put(2 * s + 1).put(next)
        }
        idx.flip()
        pathIbo = glGenBuffers(); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, pathIbo); glBufferData(GL_ELEMENT_ARRAY_BUFFER, idx, GL_STATIC_DRAW)
        MemoryUtil.memFree(idx)
        glBindVertexArray(0)
        setColors(colors ?: Coloring.colors(p, ColorBy.FEATURE).first)
        if (p.travels > 0) {
            val tb = MemoryUtil.memAllocFloat(p.travelXyz.size); tb.put(p.travelXyz).flip()
            travelVao = glGenVertexArrays(); glBindVertexArray(travelVao)
            travelVbo = glGenBuffers(); glBindBuffer(GL_ARRAY_BUFFER, travelVbo); glBufferData(GL_ARRAY_BUFFER, tb, GL_STATIC_DRAW)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L); glEnableVertexAttribArray(0)
            glBindVertexArray(0)
            MemoryUtil.memFree(tb)
            travelStart = p.travelLayerStart.copyOf()
        }
        layerStart = p.layerStart.copyOf()
        pathBounds = p.bounds
        rebuildGrid()
    }

    /** A new color per segment (RGB), both ends alike. */
    fun setColors(rgb: IntArray) {
        if (pathColVbo == 0) return
        val buf = MemoryUtil.memAlloc(rgb.size * 8)
        for (c in rgb) { val v = Palette.pack(c); buf.putInt(v).putInt(v) }
        buf.flip()
        glBindBuffer(GL_ARRAY_BUFFER, pathColVbo); glBufferSubData(GL_ARRAY_BUFFER, 0L, buf)
        MemoryUtil.memFree(buf)
    }

    /** A 10 mm grid on z = 0 under what is shown, 20 mm beyond it each way. */
    private fun rebuildGrid() {
        if (gridVao != 0) { glDeleteVertexArrays(gridVao); glDeleteBuffers(gridVbo); gridVao = 0 }
        val b = bounds(); val step = 10.0
        val x0 = floor((b[0] - 20) / step) * step; val x1 = floor((b[3] + 20) / step + 1) * step
        val y0 = floor((b[1] - 20) / step) * step; val y1 = floor((b[4] + 20) / step + 1) * step
        val lines = ArrayList<Float>()
        var x = x0; while (x <= x1 + 1e-9) { lines += listOf(x.toFloat(), y0.toFloat(), 0f, x.toFloat(), y1.toFloat(), 0f); x += step }
        var y = y0; while (y <= y1 + 1e-9) { lines += listOf(x0.toFloat(), y.toFloat(), 0f, x1.toFloat(), y.toFloat(), 0f); y += step }
        val buf = MemoryUtil.memAllocFloat(lines.size); lines.forEach { buf.put(it) }; buf.flip()
        gridVao = glGenVertexArrays(); glBindVertexArray(gridVao)
        gridVbo = glGenBuffers(); glBindBuffer(GL_ARRAY_BUFFER, gridVbo); glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L); glEnableVertexAttribArray(0)
        glVertexAttrib4f(1, 0.55f, 0.57f, 0.60f, 1f)
        glBindVertexArray(0)
        MemoryUtil.memFree(buf)
        gridVerts = lines.size / 3
    }

    fun frame(w: Int, h: Int, cam: Camera, look: Look): Frame {
        if (disposed) return Frame(w, h, ByteArray(w * h * 4), 0.0, 0.0)
        ensureFbo(w, h)
        val t0 = System.nanoTime()
        glBindFramebuffer(GL_FRAMEBUFFER, msFbo)
        glViewport(0, 0, w, h)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        // background: a soft gradient, light or dark (the window)
        glDisable(GL_DEPTH_TEST); glUseProgram(bgProg)
        if (dark) { glUniform3f(glGetUniformLocation(bgProg, "uTop"), 0.125f, 0.14f, 0.165f); glUniform3f(glGetUniformLocation(bgProg, "uBottom"), 0.075f, 0.085f, 0.10f) }
        else { glUniform3f(glGetUniformLocation(bgProg, "uTop"), 0.93f, 0.93f, 0.94f); glUniform3f(glGetUniformLocation(bgProg, "uBottom"), 0.80f, 0.81f, 0.83f) }
        glBindVertexArray(bgVao); glDrawArrays(GL_TRIANGLES, 0, 3)
        glEnable(GL_DEPTH_TEST)
        val b = bounds()
        val radius = sqrt((b[3] - b[0]).let { it * it } + (b[4] - b[1]).let { it * it } + (b[5] - b[2]).let { it * it }) / 2 + 30
        val vp = mul(cam.projection(w.toDouble() / h, radius, flipY = true), cam.view())
        // grid and toolpaths
        glUseProgram(lineProg)
        glUniformMatrix4fv(glGetUniformLocation(lineProg, "uVP"), false, vp)
        glBindVertexArray(gridVao)
        if (dark) glVertexAttrib4f(1, 0.26f, 0.29f, 0.33f, 1f) else glVertexAttrib4f(1, 0.55f, 0.57f, 0.60f, 1f)
        glDrawArrays(GL_LINES, 0, gridVerts)
        if (look.showPaths && pathVao != 0) {
            val layers = layerStart.size - 1
            val lo = look.layerLo.coerceIn(0, layers - 1); val hi = look.layerHi.coerceIn(lo, layers - 1)
            val end = look.topMoves?.let { layerStart[hi] + it.coerceIn(0, layerStart[hi + 1] - layerStart[hi]) } ?: layerStart[hi + 1]
            glUseProgram(pathProg)
            glUniformMatrix4fv(glGetUniformLocation(pathProg, "uVP"), false, vp)
            val lt = light(cam)
            glUniform3f(glGetUniformLocation(pathProg, "uLight"), lt[0], lt[1], lt[2])
            glUniform1f(glGetUniformLocation(pathProg, "uDefaultW"), DEFAULT_WIDTH_MM)
            glUniform1f(glGetUniformLocation(pathProg, "uDefaultH"), DEFAULT_HEIGHT_MM)
            glUniform1uiv(glGetUniformLocation(pathProg, "uHidden"), look.hidden)
            glBindVertexArray(pathVao)
            glDrawElements(org.lwjgl.opengl.GL32.GL_LINES_ADJACENCY, (end - layerStart[lo]) * 4, GL_UNSIGNED_INT, layerStart[lo] * 16L)
            if (look.travels && travelVao != 0) {
                glUseProgram(lineProg)
                glBindVertexArray(travelVao)
                glVertexAttrib4f(1, 0.25f, 0.45f, 0.95f, 1f)
                glDrawArrays(GL_LINES, travelStart[lo] * 2, (travelStart[hi + 1] - travelStart[lo]) * 2)
            }
        }
        // the model, see-through over toolpaths
        if (meshVao != 0 && look.meshAlpha > 0f) {
            glUseProgram(meshProg)
            glUniformMatrix4fv(glGetUniformLocation(meshProg, "uVP"), false, vp)
            val lt = light(cam)
            glUniform3f(glGetUniformLocation(meshProg, "uLight"), lt[0], lt[1], lt[2])
            glUniform1f(glGetUniformLocation(meshProg, "uAlpha"), look.meshAlpha)
            val seeThrough = look.meshAlpha < 1f
            // the color blended, the stored alpha kept at 1: blended too (glBlendFunc), every surface of the ghost behind
            // another would multiply it by 1 - alpha, and the frame, drawn over the stage's background, would wash toward it
            // (the toolpaths nearly white in the light theme, dimmed in the dark one)
            if (seeThrough) { glEnable(GL_BLEND); org.lwjgl.opengl.GL14.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ZERO, org.lwjgl.opengl.GL11.GL_ONE); glDepthMask(false) }
            glBindVertexArray(meshVao); glDrawArrays(GL_TRIANGLES, 0, meshVerts)
            if (seeThrough) { glDisable(GL_BLEND); glDepthMask(true) }
        }
        glBindVertexArray(0)
        // resolve the samples, then read back
        glBindFramebuffer(GL_READ_FRAMEBUFFER, msFbo); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, outFbo)
        glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST)
        glFinish()
        val t1 = System.nanoTime()
        glBindFramebuffer(GL_READ_FRAMEBUFFER, outFbo)
        val rb = readBuf!!; rb.clear()
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rb)
        val bytes = ByteArray(w * h * 4); rb.get(bytes)
        val t2 = System.nanoTime()
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        return Frame(w, h, bytes, (t1 - t0) / 1e6, (t2 - t1) / 1e6)
    }

    /** The light: from near the eye, a little above and to the right of it (unit). */
    private fun light(cam: Camera): FloatArray {
        val bk = cam.back(); val up = cam.up(); val rt = cam.right()
        val l = DoubleArray(3) { bk[it] + 0.35 * up[it] + 0.2 * rt[it] }; val n = sqrt(Camera.dot(l, l))
        return FloatArray(3) { (l[it] / n).toFloat() }
    }

    private fun ensureFbo(w: Int, h: Int) {
        if (w == fbW && h == fbH && msFbo != 0) return
        if (msFbo != 0) {
            glDeleteFramebuffers(msFbo); glDeleteRenderbuffers(msColor); glDeleteRenderbuffers(msDepth)
            glDeleteFramebuffers(outFbo); glDeleteRenderbuffers(outColor)
            readBuf?.let { MemoryUtil.memFree(it) }
        }
        msFbo = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, msFbo)
        msColor = glGenRenderbuffers(); glBindRenderbuffer(GL_RENDERBUFFER, msColor)
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, SAMPLES, GL_RGBA8, w, h)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, msColor)
        msDepth = glGenRenderbuffers(); glBindRenderbuffer(GL_RENDERBUFFER, msDepth)
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, SAMPLES, GL_DEPTH_COMPONENT24, w, h)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, msDepth)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "the multisampled framebuffer is incomplete" }
        outFbo = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, outFbo)
        outColor = glGenRenderbuffers(); glBindRenderbuffer(GL_RENDERBUFFER, outColor)
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, outColor)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "the read-back framebuffer is incomplete" }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        readBuf = MemoryUtil.memAlloc(w * h * 4)
        fbW = w; fbH = h
    }

    fun dispose() {
        disposed = true
        readBuf?.let { MemoryUtil.memFree(it) }; readBuf = null
    }
    /** Set by [dispose]: a frame asked for after it (the frame thread racing the viewport's close) is blank, not a crash. */
    @Volatile private var disposed = false

    private fun program(vs: String, fs: String, gs: String? = null): Int {
        fun shader(type: Int, src: String): Int {
            val s = glCreateShader(type); glShaderSource(s, src); glCompileShader(s)
            check(glGetShaderi(s, GL_COMPILE_STATUS) == GL_TRUE) { "shader: " + glGetShaderInfoLog(s) }
            return s
        }
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs)); glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
        gs?.let { glAttachShader(p, shader(org.lwjgl.opengl.GL32.GL_GEOMETRY_SHADER, it)) }
        glLinkProgram(p)
        check(glGetProgrami(p, GL_LINK_STATUS) == GL_TRUE) { "program: " + glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        /** Multisampling for smooth lines and edges. plymeld's choice. */
        const val SAMPLES = 4
        /**
         * The width a line is drawn at when its file gives none (no `;WIDTH:`). plymeld's choice, a display
         * stand-in only: about a common 0.4 mm nozzle's line, and nothing reads it as a measurement.
         */
        const val DEFAULT_WIDTH_MM = 0.42f
        /** The layer height a line is drawn with when its file gives none (no `;HEIGHT:`): a display stand-in, as above. */
        const val DEFAULT_HEIGHT_MM = 0.2f

        // Toolpaths: each segment widened on the GPU into a strip facing the eye, as wide as the bead seen from there (its
        // cross-section an ellipse, the line width across and the layer height tall: from above a strip the width wide,
        // from the side one the height tall), with round caps that close corners; shaded as a round bead. Hidden features
        // are dropped here.
        private const val PATH_VS = """#version 330 core
layout(location=0) in vec3 aPos; layout(location=1) in float aW; layout(location=2) in uint aF; layout(location=3) in vec4 aCol;
layout(location=4) in float aH;
out vec3 gPos; out float gW; out float gH; flat out uint gF; out vec4 gCol;
void main(){ gPos = aPos; gW = aW; gH = aH; gF = aF; gCol = aCol; gl_Position = vec4(aPos, 1.0); }"""
        // Each segment a six-sided prism round its center line: the bead's cross-section, the line width across and the
        // layer height tall (a flattened hexagon), lit from near the eye, its ends closed, half a width longer at each end
        // so corners close. Real geometry, so it reads right from every angle: flat strips facing the eye showed round
        // discs a line width across at corners seen end-on.
        private const val PATH_GS = """#version 330 core
layout(lines_adjacency) in; layout(triangle_strip, max_vertices = 36) out;
in vec3 gPos[]; in float gW[]; in float gH[]; flat in uint gF[]; in vec4 gCol[];
uniform mat4 uVP; uniform uint uHidden[4]; uniform float uDefaultW; uniform float uDefaultH;
out vec4 fCol; out vec3 fN;
void main(){
  uint f = gF[1];
  if (((uHidden[f >> 5u] >> (f & 31u)) & 1u) != 0u) return;
  vec3 a = gPos[1]; vec3 b = gPos[2]; vec3 d = b - a; float len = length(d);
  if (len < 1e-6) return;
  vec3 dir = d / len;
  float w = isnan(gW[1]) ? uDefaultW : gW[1];
  float h = isnan(gH[1]) ? uDefaultH : gH[1];
  vec3 hp = cross(dir, vec3(0.0, 0.0, 1.0));
  hp = length(hp) < 1e-3 ? vec3(1.0, 0.0, 0.0) : normalize(hp);
  vec3 vp = normalize(cross(hp, dir));
  // the joints: mitred on the plane halfway between this segment and a neighbor that touches it (gPos[0] / gPos[3]
  // are this segment's own ends when there is none); a turn sharper than about 110 deg is left square, so no spike
  vec3 pd = gPos[1] - gPos[0]; vec3 nd = gPos[3] - gPos[2];
  bool hasPrev = length(pd) > 1e-6 && dot(normalize(pd), dir) > -0.35;
  bool hasNext = length(nd) > 1e-6 && dot(normalize(nd), dir) > -0.35;
  vec3 mS = hasPrev ? normalize(normalize(pd) + dir) : dir;
  vec3 mE = hasNext ? normalize(dir + normalize(nd)) : dir;
  vec3 p[6]; vec3 n[6]; vec3 s[6]; vec3 e[6];
  for (int k = 0; k < 6; k++) {
    float t = radians(60.0 * float(k));
    p[k] = hp * (0.5 * w * cos(t)) + vp * (0.5 * h * sin(t));
    float m = radians(60.0 * float(k) + 30.0);   // the face from corner k to k+1, its normal the ellipse's there
    n[k] = normalize(hp * (cos(m) / w) + vp * (sin(m) / h));
    s[k] = a + p[k] - dir * (dot(p[k], mS) / dot(dir, mS));
    e[k] = b + p[k] - dir * (dot(p[k], mE) / dot(dir, mE));
  }
  vec4 ca = gCol[1]; vec4 cb = gCol[2];
  for (int k = 0; k < 6; k++) {
    int j = (k + 1) % 6;
    fN = n[k];
    fCol = ca; gl_Position = uVP * vec4(s[k], 1.0); EmitVertex();
    fCol = ca; gl_Position = uVP * vec4(s[j], 1.0); EmitVertex();
    fCol = cb; gl_Position = uVP * vec4(e[k], 1.0); EmitVertex();
    fCol = cb; gl_Position = uVP * vec4(e[j], 1.0); EmitVertex();
    EndPrimitive();
  }
  // an end with no neighbor is closed: a hexagon as one strip, corners 0, 1, 5, 2, 4, 3
  int order[6] = int[6](0, 1, 5, 2, 4, 3);
  if (!hasPrev) { fN = -dir; fCol = ca; for (int i = 0; i < 6; i++) { gl_Position = uVP * vec4(s[order[i]], 1.0); EmitVertex(); } EndPrimitive(); }
  if (!hasNext) { fN = dir; fCol = cb; for (int i = 0; i < 6; i++) { gl_Position = uVP * vec4(e[order[i]], 1.0); EmitVertex(); } EndPrimitive(); }
}"""
        private const val PATH_FS = """#version 330 core
in vec4 fCol; in vec3 fN; uniform vec3 uLight; out vec4 o;
void main(){ float d = max(dot(normalize(fN), uLight), 0.0); o = vec4(fCol.rgb * (0.30 + 0.70 * d), 1.0); }"""

        /** a * b, 4 x 4 column-major. */
        fun mul(a: FloatArray, b: FloatArray): FloatArray = FloatArray(16) { i -> val c = i / 4; val r = i % 4; (0..3).sumOf { k -> (a[k * 4 + r] * b[c * 4 + k]).toDouble() }.toFloat() }

        /** The model's color when no part colors are given (a blue that reads against the gray background). */
        const val DEFAULT_MESH_RGB = 0x5C8FD1
        private const val MESH_VS = """#version 330 core
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNrm; layout(location=2) in vec3 aCol;
uniform mat4 uVP; out vec3 vN; out vec3 vC;
void main(){ vN = aNrm; vC = aCol; gl_Position = uVP * vec4(aPos, 1.0); }"""
        private const val MESH_FS = """#version 330 core
in vec3 vN; in vec3 vC; uniform vec3 uLight; uniform float uAlpha; out vec4 o;
void main(){ float d = abs(dot(normalize(vN), uLight)); o = vec4(vC * (0.32 + 0.68 * d), uAlpha); }"""
        private const val LINE_VS = """#version 330 core
layout(location=0) in vec3 aPos; layout(location=1) in vec4 aCol;
uniform mat4 uVP; out vec4 vCol;
void main(){ vCol = aCol; gl_Position = uVP * vec4(aPos, 1.0); }"""
        private const val LINE_FS = """#version 330 core
in vec4 vCol; out vec4 o; void main(){ o = vCol; }"""
        private const val BG_VS = """#version 330 core
out float vY;
void main(){ vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2); vY = p.y; gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }"""
        // drawn in clip space (no flip), read back bottom row first, and shown as the top: vY = 0 is the picture's top, light
        private const val BG_FS = """#version 330 core
in float vY; uniform vec3 uTop; uniform vec3 uBottom; out vec4 o;
void main(){ o = vec4(mix(uTop, uBottom, clamp(vY, 0.0, 1.0)), 1.0); }"""
    }
}

/**
 * Colors by feature (the names g-code files give them). plymeld's choice, a placeholder palette: walls warm,
 * fills cool, support gray; a name not listed is mid gray. Packed little-endian as the buffer stores them (R first).
 */
object Palette {
    private val named = mapOf(
        "Outer wall" to 0xE8743B, "External perimeter" to 0xE8743B, "Inner wall" to 0xF2B134, "Perimeter" to 0xF2B134,
        "Overhang wall" to 0x2E86DE, "Sparse infill" to 0xB03A48, "Internal infill" to 0xB03A48,
        "Internal solid infill" to 0xA55EEA, "Solid infill" to 0xA55EEA, "Top surface" to 0xEB3B5A, "Bottom surface" to 0x4B7BEC,
        "Bridge" to 0x20BF6B, "Internal Bridge" to 0x0FB9B1, "Gap infill" to 0xFED330, "Ironing" to 0xFA8231,
        "Support" to 0x8E8E8E, "Support interface" to 0x5F5F5F, "Prime tower" to 0x778CA3, "Skirt" to 0x45AAF2, "Brim" to 0x45AAF2,
        "Prime line" to 0x111827, "Wave overhang" to 0x26C6DA,
    )
    fun rgb(feature: String): Int = named[feature] ?: 0x9AA0A6
    /** RGB as the color buffer stores it: R, G, B, A bytes in memory order (an int written little-endian). */
    fun pack(c: Int): Int = (0xFF shl 24) or ((c and 0xFF) shl 16) or (c and 0xFF00) or ((c shr 16) and 0xFF)
}
