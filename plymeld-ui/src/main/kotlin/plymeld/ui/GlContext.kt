package plymeld.ui

import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11

/**
 * An OpenGL 3.3 core context with no visible window (a hidden GLFW window), current on the thread that made it. The 3D
 * view draws into a framebuffer of its own in it and is read back as an image for the Compose window.
 * GLFW asks every call on its windows to come from the thread that created them: [GlThread] keeps it on one.
 */
class GlContext private constructor(private val window: Long) : AutoCloseable {
    /** What the driver says it is: the vendor, renderer, and version strings. */
    val describe: String get() = "${GL11.glGetString(GL11.GL_VENDOR)} / ${GL11.glGetString(GL11.GL_RENDERER)} / ${GL11.glGetString(GL11.GL_VERSION)}"

    /**
     * The window goes; GLFW is not terminated. glfwTerminate unloads the GL library while Mesa's d3d12 driver (WSL's GPU)
     * still has threads running in it: the JVM died in libc after a clean benchmark (SIGSEGV at an unmapped
     * address on a native thread). The process's exit releases it all.
     */
    override fun close() {
        GLFW.glfwMakeContextCurrent(0L)
        GLFW.glfwDestroyWindow(window)
    }

    companion object {
        /** [platform]: GLFW's own choice when null, or "x11" / "wayland" (a diagnostic: which gets the GPU). */
        fun open(platform: String? = null): GlContext {
            GLFWErrorCallback.createPrint(System.err).set()
            when (platform) {
                null -> {}
                "x11" -> GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11)
                "wayland" -> GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_WAYLAND)
                else -> error("unknown GLFW platform '$platform' (x11, wayland)")
            }
            check(GLFW.glfwInit()) { "GLFW could not start: no display for OpenGL (on Linux, DISPLAY or WAYLAND_DISPLAY)" }
            GLFW.glfwDefaultWindowHints()
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE)
            val w = GLFW.glfwCreateWindow(16, 16, "plymeld gl", 0L, 0L)
            check(w != 0L) { "no OpenGL 3.3 core context on this machine" }
            GLFW.glfwMakeContextCurrent(w)
            GL.createCapabilities()
            return GlContext(w)
        }
    }
}
