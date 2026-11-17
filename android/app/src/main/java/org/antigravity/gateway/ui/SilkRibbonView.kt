package org.antigravity.gateway.ui

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.content.res.Resources
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
        private var uDensity = 0
        private var quad: FloatBuffer? = null
        private var lastFrameNs = 0L
        private var elapsed = 0f
        private var surfaceWidth = 1
        private var surfaceHeight = 1

        // Offscreen pass, used only when previewDownscale < 1.
        private var blitProgram = 0
        private var blitAPos = 0
        private var blitTex = 0
        private var sceneTexture = 0
        private var sceneFramebuffer = 0
        private var sceneWidth = 0
        private var sceneHeight = 0

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)

            // The fabric tap count is compiled in, so the fragment source is patched before linking.
            val fragmentSource = FRAGMENT_SHADER
                .replace("%TAPS%", previewFabricTaps.coerceIn(1, 3).toString())
                .replace(
                    "%FABRIC%",
                    "fabricAvg(uv * grain * 0.32, FABRIC_TAPS)"
                )

            val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
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
            uDensity = GLES20.glGetUniformLocation(program, "uDensity")

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

            buildBlitProgram()
            lastFrameNs = 0L
            elapsed = 0f
        }

        private fun buildBlitProgram() {
            val v = compile(GLES20.GL_VERTEX_SHADER, BLIT_VERTEX_SHADER)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, BLIT_FRAGMENT_SHADER)
            if (v == 0 || f == 0) return
            val linked = GLES20.glCreateProgram()
            GLES20.glAttachShader(linked, v)
            GLES20.glAttachShader(linked, f)
            GLES20.glLinkProgram(linked)
            val status = IntArray(1)
            GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(v)
            GLES20.glDeleteShader(f)
            if (status[0] != GLES20.GL_TRUE) {
                GLES20.glDeleteProgram(linked)
                return
            }
            blitProgram = linked
            blitAPos = GLES20.glGetAttribLocation(blitProgram, "aPos")
            blitTex = GLES20.glGetUniformLocation(blitProgram, "uTex")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)
            GLES20.glViewport(0, 0, width, height)

            val scale = previewDownscale.coerceIn(0.15f, 1f)
            sceneWidth = (surfaceWidth * scale).toInt().coerceAtLeast(1)
            sceneHeight = (surfaceHeight * scale).toInt().coerceAtLeast(1)
            if (scale < 1f && blitProgram != 0) setupSceneTarget() else releaseSceneTarget()
        }

        private fun setupSceneTarget() {
            releaseSceneTarget()
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, sceneWidth, sceneHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val fbo = IntArray(1)
            GLES20.glGenFramebuffers(1, fbo, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tex[0], 0
            )
            val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
                GLES20.GL_FRAMEBUFFER_COMPLETE
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (!complete) {
                GLES20.glDeleteTextures(1, tex, 0)
                GLES20.glDeleteFramebuffers(1, fbo, 0)
                return
            }
            sceneTexture = tex[0]
            sceneFramebuffer = fbo[0]
        }

        private fun releaseSceneTarget() {
            if (sceneFramebuffer != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(sceneFramebuffer), 0)
                sceneFramebuffer = 0
            }
            if (sceneTexture != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(sceneTexture), 0)
                sceneTexture = 0
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            if (program == 0) return
            val buffer = quad ?: return

            val now = System.nanoTime()
            if (lastFrameNs == 0L) lastFrameNs = now
            val dt = ((now - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            lastFrameNs = now
            elapsed = if (previewFreezeSeconds >= 0f) previewFreezeSeconds else elapsed + dt * SPEED

            val offscreen = sceneFramebuffer != 0
            val targetWidth = if (offscreen) sceneWidth else surfaceWidth
            val targetHeight = if (offscreen) sceneHeight else surfaceHeight

            if (offscreen) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFramebuffer)
                GLES20.glViewport(0, 0, targetWidth, targetHeight)
            }

            GLES20.glClearColor(VOID_R, VOID_G, VOID_B, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, buffer)

            // Everything the shader reasons about is in dp, matching the CSS pixels the preset was
            // tuned on. Feeding it device pixels made the weave density track the panel instead of
            // the layout: on a 540dp screen the cells came out 45% coarser than the 420px page.
            val density = Resources.getSystem().displayMetrics.density.coerceAtLeast(1f)
            GLES20.glUniform2f(
                uRes,
                (targetWidth / density).toFloat(),
                (targetHeight / density).toFloat()
            )
            GLES20.glUniform1f(uDensity, density)
            GLES20.glUniform1f(uTime, elapsed)
            GLES20.glUniform1f(uZoom, ZOOM)
            GLES20.glUniform1f(uChurn, CHURN)
            GLES20.glUniform1f(uFold, FOLD)
            GLES20.glUniform1f(uRim, RIM)
            GLES20.glUniform1f(uContrast, CONTRAST)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
            GLES20.glDisableVertexAttribArray(aPos)

            if (offscreen) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
                GLES20.glClearColor(VOID_R, VOID_G, VOID_B, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                blitScene(buffer)
            }
        }

        private fun blitScene(buffer: FloatBuffer) {
            if (blitProgram == 0) return
            GLES20.glUseProgram(blitProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTexture)
            GLES20.glUniform1i(blitTex, 0)
            GLES20.glEnableVertexAttribArray(blitAPos)
            GLES20.glVertexAttribPointer(blitAPos, 2, GLES20.GL_FLOAT, false, 0, buffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
            GLES20.glDisableVertexAttribArray(blitAPos)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
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

    companion object {
        /**
         * Preview-only. 1.0 shades the surface at panel resolution; below 1.0 the scene is rendered
         * on a smaller offscreen buffer and blitted with bilinear filtering, which is what the
         * browser effectively does when it stretches its 420px canvas.
         */
        @JvmStatic
        var previewDownscale = 1.0f

        /** Preview-only. Fabric taps per axis: 1 point-samples, 2 and 3 average a 2x2 / 3x3 grid. */
        @JvmStatic
        var previewFabricTaps = 1

        /** Preview-only. Freezes the animation clock at this many seconds; negative keeps it live. */
        @JvmStatic
        var previewFreezeSeconds = -1f

        private const val FRAME_MS = 33L

        // Matched to the tuned preset: the same numbers the design preview renders with.
        private const val ZOOM = 4.8f
        private const val CHURN = 0.48f
        private const val FOLD = 18.0f
        private const val RIM = 0.52f
        private const val CONTRAST = 1.07f
        private const val SPEED = 0.91f

        // Deepest stop of the shader ramp, used when the program is unavailable.
        private const val VOID_R = 0.010f
        private const val VOID_G = 0.016f
        private const val VOID_B = 0.038f

        private const val VERTEX_SHADER = """
attribute vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

        private const val BLIT_VERTEX_SHADER = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        private const val BLIT_FRAGMENT_SHADER = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;
void main() { gl_FragColor = texture2D(uTex, vUv); }
"""

        private const val FRAGMENT_SHADER = """
precision highp float;
uniform vec2 uRes;
uniform float uTime, uZoom, uRim, uContrast, uChurn, uFold, uDensity;

const int FABRIC_TAPS = %TAPS%;

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

// Grid-averaged weave. taps = 1 is a single point sample; 2 and 3 average a 2x2 / 3x3 grid.
//
// The step has to be a fraction of the weave period, not of the canvas: noise() runs at
// sin(p.y * 1444.0), so its period is 2*pi/1444 = 0.00435 and each matrix multiply in fabric()
// halves it, down to 0.00054 for the finest octave. Stepping by 1/grain instead moved the taps
// 18 whole periods apart, which averages independent random values and leaves the noise intact.
float fabricAvg(vec2 p, int taps) {
    float step = 0.0016 / float(taps);
    float acc = 0.0;
    float count = 0.0;
    for (int i = 0; i < 3; i++) {
        for (int j = 0; j < 3; j++) {
            if (i < taps && j < taps) {
                vec2 offset = (vec2(float(i), float(j)) - (float(taps) - 1.0) * 0.5) * step;
                acc += fabric(p + offset);
                count += 1.0;
            }
        }
    }
    return acc / count;
}

float silk(vec2 uv, float t, float grain, float fold) {
    float s = sin(5.0 * (uv.x + uv.y) + fold * cos(2.0 * uv.x + 5.0 * uv.y)
                  + sin(12.0 * (uv.x + uv.y)) - t);
    s = 0.7 + 0.3 * (s * s * 0.5 + s);
    // Softer than the 0.18 in the browser build: without MSAA the full amplitude reads as grain
    // rather than as fabric.
    s *= 0.98 + 0.045 * %FABRIC%;
    return s * 0.9 + 0.1;
}

float silkd(vec2 uv, float t, float fold) {
    float xy = uv.x + uv.y;
    float d = (5.0 - 2.0 * fold * sin(2.0 * uv.x + 5.0 * uv.y) + 12.0 * cos(12.0 * xy))
              * cos(5.0 * xy + fold * cos(2.0 * uv.x + 5.0 * uv.y) + sin(12.0 * xy) - t);
    return 0.004 * d * (sign(d) + 3.0);
}

void main() {
    // dp, not device pixels: the preset's frequencies are all authored against CSS pixels.
    vec2 frag = gl_FragCoord.xy / uDensity;
    float grain = min(uRes.x, uRes.y) * 2.0;
    float base = max(uRes.x, uRes.y);
    vec2 uv = (frag - 0.5 * uRes) / base * 2.0 + 0.5;
    uv /= uZoom;
    float t = uTime;
    vec2 q = uv;
    q += uChurn * 0.17 * vec2(
        sin(uv.y * 2.30 + t * 0.52) + 0.45 * sin(uv.y * 4.10 - t * 0.34),
        cos(uv.x * 2.05 - t * 0.47) + 0.45 * cos(uv.x * 3.65 + t * 0.29));
    q.y += uChurn * 0.078 * sin(7.0 * q.x - t);
    // The drift is a speed modulation, so the phase has to be its integral: the closed form below
    // keeps the offset bounded at 0.40*uChurn/0.21 rad. Multiplying t by the modulation instead
    // (the earlier form) grew that offset without limit and curled the folds into rings over time.
    float tp = t - (0.40 * uChurn / 0.21) * cos(q.x * 2.55 + q.y * 1.85 + t * 0.21);
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
    // The vignette is a radial gradient that spans only ~45 of the 256 available levels, so an
    // 8-bit target cuts its smooth falloff into concentric contours. Half a level of ordered
    // dither trades those contours for noise below the display's resolution, and leaves the
    // gradient itself untouched.
    float dither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    col += (dither - 0.5) / 255.0;

    gl_FragColor = vec4(col, 1.0);
}
"""
    }
}
