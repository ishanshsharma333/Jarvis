package com.ishansh.jarvis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class ReactorView(ctx: Context) : View(ctx) {

    var active = false
        set(value) {
            field = value
            invalidate()
        }

    private var rotX = -0.5f
    private var rotY = 0f
    private var velX = 0f
    private var velY = 0f
    private var spin = 0f
    private var pulse = 0f
    private var time = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var touching = false

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pa = FloatArray(4)
    private val pb = FloatArray(4)
    private val dust = Array(56) { floatArrayOf(0f, 0f, 0f) }
    private var animator: ValueAnimator? = null

    init {
        val rnd = Random(7)
        for (d in dust) {
            val u = rnd.nextFloat() * 2f - 1f
            val t = rnd.nextFloat() * 6.2832f
            val r = 1.12f + rnd.nextFloat() * 0.25f
            val q = sqrt(1f - u * u)
            d[0] = r * q * cos(t)
            d[1] = r * u
            d[2] = r * q * sin(t)
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { tick() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun tick() {
        val fast = if (active) 2.4f else 1f
        time += 0.016f
        spin += 0.018f * fast
        pulse *= 0.93f
        if (!touching) {
            rotY += velY + 0.005f * fast
            rotX += velX + (-0.5f - rotX) * 0.01f
            velX *= 0.94f
            velY *= 0.94f
        }
        rotX = rotX.coerceIn(-1.4f, 1.4f)
        invalidate()
    }

    // ---------- Touch: drag to rotate, tap to start or stop ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                dragging = false
                touching = true
                velX = 0f
                velY = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.x - downX, e.y - downY) > 18f) dragging = true
                if (dragging) {
                    velY = (e.x - lastX) * 0.012f
                    velX = (e.y - lastY) * 0.012f
                    rotY += velY
                    rotX += velX
                }
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                touching = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragging) {
                    pulse = 1f
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                touching = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ---------- 3D helpers ----------

    private fun withAlpha(col: Int, alpha: Int): Int = (col and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun depthAlpha(z: Float): Int = (255 * (0.35f + 0.65f * (0.5f - z / 2.6f).coerceIn(0f, 1f))).toInt()

    private fun proj(x: Float, y: Float, z: Float, cx: Float, cy: Float, s: Float, out: FloatArray) {
        val cr = cos(rotY)
        val sr = sin(rotY)
        val x1 = x * cr + z * sr
        val z1 = -x * sr + z * cr
        val cp = cos(rotX)
        val sp = sin(rotX)
        val y2 = y * cp - z1 * sp
        val z2 = y * sp + z1 * cp
        val f = 3.6f / (3.6f + z2)
        out[0] = cx + x1 * s * f
        out[1] = cy + y2 * s * f
        out[2] = z2
        out[3] = f
    }

    private fun seg(
        c: Canvas, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
        col: Int, w: Float, cx: Float, cy: Float, s: Float
    ) {
        proj(x1, y1, z1, cx, cy, s, pa)
        proj(x2, y2, z2, cx, cy, s, pb)
        line.color = withAlpha(col, depthAlpha((pa[2] + pb[2]) / 2f))
        line.strokeWidth = w * (pa[3] + pb[3]) / 2f
        c.drawLine(pa[0], pa[1], pb[0], pb[1], line)
    }

    private fun arc(
        c: Canvas, r: Float, z0: Float, plane: Int, startDeg: Float, lenDeg: Float,
        col: Int, w: Float, cx: Float, cy: Float, s: Float
    ) {
        val steps = maxOf(2, (lenDeg / 6f).toInt())
        var px = 0f
        var py = 0f
        var pz = 0f
        for (i in 0..steps) {
            val t = (startDeg + lenDeg * i / steps) * 0.0174533f
            val u = r * cos(t)
            val v = r * sin(t)
            val x: Float
            val y: Float
            val z: Float
            when (plane) {
                0 -> { x = u; y = v; z = z0 }
                1 -> { x = u; y = z0; z = v }
                else -> { x = z0; y = u; z = v }
            }
            if (i > 0) seg(c, px, py, pz, x, y, z, col, w, cx, cy, s)
            px = x
            py = y
            pz = z
        }
    }

    // ---------- Drawing ----------

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val s = min(cx, cy) * 0.74f
        val dp = resources.displayMetrics.density
        val main = if (active) 0xFF3FB67A.toInt() else 0xFF4DE3FF.toInt()
        val gold = 0xFFF5B301.toInt()
        val red = 0xFFE5484D.toInt()
        val purple = 0xFF9B5CFF.toInt()
        val sp = spin * 57.2958f

        // soft backdrop glow
        fill.shader = RadialGradient(cx, cy, s * 1.3f, withAlpha(main, 80), withAlpha(main, 0), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, s * 1.3f, fill)

        // hexagon prism frame
        for (layer in 0..1) {
            val z = if (layer == 0) -0.28f else 0.28f
            for (k in 0 until 6) {
                val a1 = k * 1.0472f
                val a2 = (k + 1) * 1.0472f
                seg(c, cos(a1) * 1.18f, sin(a1) * 1.18f, z, cos(a2) * 1.18f, sin(a2) * 1.18f, z, purple, 1.6f * dp, cx, cy, s)
            }
        }
        for (k in 0 until 6) {
            val a1 = k * 1.0472f
            seg(c, cos(a1) * 1.18f, sin(a1) * 1.18f, -0.28f, cos(a1) * 1.18f, sin(a1) * 1.18f, 0.28f, purple, 1.6f * dp, cx, cy, s)
        }

        // gyroscope rings in other planes
        arc(c, 1.02f, 0f, 1, sp * 1.4f, 300f, purple, 2.6f * dp, cx, cy, s)
        arc(c, 1.0f, 0f, 2, -sp * 1.1f, 300f, gold, 2.6f * dp, cx, cy, s)

        // three flat segmented rings at different depths
        val radii = floatArrayOf(0.42f, 0.66f, 0.9f)
        val depths = floatArrayOf(0f, 0.12f, -0.12f)
        val cols = intArrayOf(main, gold, red)
        for (i in 0 until 3) {
            val dir = if (i % 2 == 0) 1 else -1
            for (k in 0 until 4) {
                arc(c, radii[i], depths[i], 0, sp * dir * (i + 1) * 0.6f + k * 90f, 58f, cols[i], 5f * dp, cx, cy, s)
            }
        }

        // reactor coils
        for (k in 0 until 10) {
            val a1 = k * 0.62832f + spin * 0.2f
            seg(c, cos(a1) * 0.44f, sin(a1) * 0.44f, 0.06f, cos(a1) * 0.58f, sin(a1) * 0.58f, 0.06f, main, 8f * dp, cx, cy, s)
            seg(c, cos(a1) * 0.44f, sin(a1) * 0.44f, -0.06f, cos(a1) * 0.58f, sin(a1) * 0.58f, -0.06f, main, 8f * dp, cx, cy, s)
        }

        // floating particles
        val ang = time * 0.25f
        val ca = cos(ang)
        val sa = sin(ang)
        fill.shader = null
        for (d in dust) {
            val x = d[0] * ca + d[2] * sa
            val z = -d[0] * sa + d[2] * ca
            proj(x, d[1], z, cx, cy, s, pa)
            fill.color = withAlpha(0xFFFFD166.toInt(), depthAlpha(pa[2]))
            c.drawCircle(pa[0], pa[1], 2.2f * dp * pa[3], fill)
        }

        // glowing core
        proj(0f, 0f, 0f, cx, cy, s, pa)
        val cr = s * 0.24f * pa[3] * (1f + 0.07f * sin(time * 3f) + 0.45f * pulse)
        fill.shader = RadialGradient(
            pa[0], pa[1], cr * 2f,
            intArrayOf(Color.WHITE, main, withAlpha(main, 0)),
            floatArrayOf(0f, 0.38f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(pa[0], pa[1], cr * 2f, fill)
    }
}
