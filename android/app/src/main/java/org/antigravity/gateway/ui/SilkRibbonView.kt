package org.antigravity.gateway.ui

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Animated silk backdrop for the [org.antigravity.gateway.data.Skin.ACR] skin.
 *
 * The whole surface is a single full-screen triangle pair shaded by one fragment shader, so the
 * per-frame cost stays on the GPU: the CPU only posts a render request every [FRAME_MS] and feeds
 * six floats into the program. Rendering is throttled rather than free-running because an animated
 * wallpaper that runs at the display refresh rate drains the battery without looking different.
 *
 * If the driver refuses the shader the view keeps drawing the flat void colour, so a broken GL stack
 * degrades to a plain dark background instead of a black hole or a crash.
 */
class SilkRibbonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer = SilkRenderer()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val frameTick = object : Runnable {
        override fun run() {
            if (!running) return
            requestRender()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    init {
        setEGLContextClientVersion(2)
        // Request a full 8-bit colour buffer; the default RGB565 config banded badly on the
        // darkest gradient stops.
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /**
     * Reveals the backdrop and starts feeding frames. Called from the host's onResume, because a
     * GLSurfaceView also needs its own resume pass to bring the render thread back up.
     */
    fun startBackdrop() {
        visibility = VISIBLE
        onResume()
        if (running) return
        running = true
        handler.removeCallbacks(frameTick)
        handler.post(frameTick)
    }

    /** Hides the backdrop and releases the render thread. Safe to call when never started. */
    fun stopBackdrop() {
        running = false
        handler.removeCallbacks(frameTick)
        visibility = GONE
        onPause()
    }

    private class SilkRenderer : Renderer {

        private var program = 0
        private var aPos = 0
        private var uRes = 0
        private var uTime = 0
        private var uZoom = 0
        private var uRim = 0
        private var uContrast = 0
        private var uChurn = 0
        private var uFold = 0
        private var quad: FloatBuffer? = null
        private var lastFrameNs = 0L
        private var elapsed = 0f
        private var surfaceWidth = 1
        private var surfaceHeight = 1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)

            val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            if (vertex == 0 || fragment == 0) return

            val linked = GLES20.glCreateProgram()
            GLES20.glAttachShader(linked, vertex)
            GLES20.glAttachShader(linked, fragment)
            GLES20.glLinkProgram(linked)
            val status = IntArray(1)
            GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            if (status[0] != GLES20.GL_TRUE) {
                GLES20.glDeleteProgram(linked)
                return
            }

            program = linked
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            uRes = GLES20.glGetUniformLocation(program, "uRes")
            uTime = GLES20.glGetUniformLocation(program, "uTime")
            uZoom = GLES20.glGetUniformLocation(program, "uZoom")
            uRim = GLES20.glGetUniformLocation(program, "uRim")
            uContrast = GLES20.glGetUniformLocation(program, "uContrast")
            uChurn = GLES20.glGetUniformLocation(program, "uChurn")
            uFold = GLES20.glGetUniformLocation(program, "uFold")

            val data = floatArrayOf(
                -1f, -1f, 1f, -1f, -1f, 1f,
                -1f, 1f, 1f, -1f, 1f, 1f
            )
            quad = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(data)
                    position(0)
                }

            lastFrameNs = 0L
            elapsed = 0f
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(VOID_R, VOID_G, VOID_B, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (program == 0) return

            val buffer = quad ?: return
            val now = System.nanoTime()
            if (lastFrameNs == 0L) lastFrameNs = now
            val dt = ((now - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            lastFrameNs = now
            elapsed += dt * SPEED

            GLES20.glUseProgram(program)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, buffer)

            // uRes is sampled at device pixels on purpose: the grain term is the fabric texture and
            // it has to stay sharp instead of scaling with the density bucket.
            GLES20.glUniform2f(uRes, surfaceWidth.toFloat(), surfaceHeight.toFloat())
            GLES20.glUniform1f(uTime, elapsed)
            GLES20.glUniform1f(uZoom, ZOOM)
            GLES20.glUniform1f(uChurn, CHURN)
            GLES20.glUniform1f(uFold, FOLD)
            GLES20.glUniform1f(uRim, RIM)
            GLES20.glUniform1f(uContrast, CONTRAST)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
            GLES20.glDisableVertexAttribArray(aPos)
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) return 0
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) {
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
    }

    private companion object {
        const val FRAME_MS = 33L

        // Matched to the tuned preset: the same numbers the design preview renders with.
        const val ZOOM = 4.8f
        const val CHURN = 0.48f
        const val FOLD = 18.0f
        const val RIM = 0.52f
        const val CONTRAST = 1.07f
        const val SPEED = 0.91f

        // Deepest stop of the shader ramp, used when the program is unavailable.
        const val VOID_R = 0.010f
        const val VOID_G = 0.016f
        const val VOID_B = 0.038f

        const val VERTEX_SHADER = """
attribute vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

        const val FRAGMENT_SHADER = """
precision highp float;
uniform vec2 uRes;
uniform float uTime, uZoom, uRim, uContrast, uChurn, uFold;

float noise(vec2 p) {
    return smoothstep(-0.5, 0.9, sin((p.x - p.y) * 555.0) * sin(p.y * 1444.0)) - 0.4;
}

float fabric(vec2 p) {
    const mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
    float f = 0.4 * noise(p);
    f += 0.3 * noise(p = m * p);
    f += 0.2 * noise(p = m * p);
    return f + 0.1 * noise(m * p);
}

float silk(vec2 uv, float t, float grain, float fold) {
    float s = sin(5.0 * (uv.x + uv.y) + fold * cos(2.0 * uv.x + 5.0 * uv.y)
                  + sin(12.0 * (uv.x + uv.y)) - t);
    s = 0.7 + 0.3 * (s * s * 0.5 + s);
    s *= 0.95 + 0.18 * fabric(uv * grain * 0.32);
    return s * 0.9 + 0.1;
}

float silkd(vec2 uv, float t, float fold) {
    float xy = uv.x + uv.y;
    float d = (5.0 - 2.0 * fold * sin(2.0 * uv.x + 5.0 * uv.y) + 12.0 * cos(12.0 * xy))
              * cos(5.0 * xy + fold * cos(2.0 * uv.x + 5.0 * uv.y) + sin(12.0 * xy) - t);
    return 0.004 * d * (sign(d) + 3.0);
}

void main() {
    vec2 frag = gl_FragCoord.xy;
    float grain = min(uRes.x, uRes.y);
    float base = max(uRes.x, uRes.y);
    vec2 uv = (frag - 0.5 * uRes) / base * 2.0 + 0.5;
    uv /= uZoom;
    float t = uTime;
    vec2 q = uv;
    q += uChurn * 0.17 * vec2(
        sin(uv.y * 2.30 + t * 0.52) + 0.45 * sin(uv.y * 4.10 - t * 0.34),
        cos(uv.x * 2.05 - t * 0.47) + 0.45 * cos(uv.x * 3.65 + t * 0.29));
    q.y += uChurn * 0.078 * sin(7.0 * q.x - t);
    float tp = t * (1.0 + 0.40 * uChurn * sin(q.x * 2.55 + q.y * 1.85 + t * 0.21));
    float s = sqrt(max(silk(q, tp, grain, uFold), 0.0));
    float d = silkd(q, tp, uFold);
    float v = clamp((s - 0.58) * 1.55, 0.0, 1.0);
    v = clamp(v + d * 0.85 * uRim, 0.0, 1.0);
    v = pow(v, 1.22);
    v = clamp((v - 0.5) * uContrast + 0.5, 0.0, 1.0);
    vec3 cVoid = vec3(0.010, 0.016, 0.038), cDeep = vec3(0.042, 0.072, 0.160);
    vec3 cMid = vec3(0.160, 0.235, 0.420), cLit = vec3(0.430, 0.530, 0.750);
    vec3 cCrest = vec3(0.820, 0.875, 0.960);
    vec3 col = mix(cVoid, cDeep, smoothstep(0.00, 0.52, v));
    col = mix(col, cMid, smoothstep(0.26, 0.76, v));
    col = mix(col, cLit, smoothstep(0.58, 0.94, v));
    col = mix(col, cCrest, smoothstep(0.86, 1.00, v));
    float vig = smoothstep(1.50, 0.30, length((frag / uRes - 0.5) * vec2(1.05, 1.0)) * 1.5);
    col *= mix(0.55, 1.0, vig);
    col = col / (col + 0.90) * 1.62;
    gl_FragColor = vec4(col, 1.0);
}
"""
    }
}
