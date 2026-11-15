package com.ishansh.jarvis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

class ReactorView(ctx: Context) : View(ctx) {

    var active = false
        set(value) {
            field = value
            invalidate()
        }

    private var angle = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 6000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) * 0.92f
        val main = if (active) 0xFF3FB67A.toInt() else 0xFF4DE3FF.toInt()

        glow.shader = RadialGradient(cx, cy, r * 0.5f, main, 0x00000000, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.5f, glow)

        val colors = intArrayOf(0xFF4DE3FF.toInt(), 0xFFF5B301.toInt(), 0xFFE5484D.toInt())
        val sizes = floatArrayOf(0.58f, 0.78f, 0.97f)
        for (i in colors.indices) {
            ring.color = colors[i]
            ring.strokeWidth = r * 0.06f
            val rr = r * sizes[i]
            val rect = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
            val dir = if (i % 2 == 0) 1 else -1
            for (s in 0 until 4) {
                c.drawArc(rect, angle * dir * (i + 1) * 0.5f + s * 90f, 55f, false, ring)
            }
        }
        ring.color = main
        ring.strokeWidth = r * 0.04f
        c.drawCircle(cx, cy, r * 0.32f, ring)
    }
}
