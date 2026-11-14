package com.ontimepickup.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 브랜드 로고의 카운트다운 링. 12시에서 시계 방향으로 남은 비율만큼 호를 그리고,
 * 호 끝에 다가오는 차를 뜻하는 점을 찍는다. 남은 시간이 줄면 호가 짧아진다.
 */
class CountdownRing(context: Context, private val track: Int, private val accent: Int, private val dot: Int) : View(context) {
    private var fraction = 1f
    private val stroke = Pleos.dp(context, 18f)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = track }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = accent; strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dot }
    private val box = RectF()

    /** [from]이 있으면 그 값에서 새 값으로 부드럽게 줄어든다(ETA는 15초마다 갱신). */
    fun setFraction(value: Float, from: Float? = null) {
        val to = value.coerceIn(0f, 1f)
        if (from == null || from == to) { fraction = to; invalidate(); return }
        fraction = from
        ValueAnimator.ofFloat(from, to).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) / 2f - stroke
        val cx = width / 2f
        val cy = height / 2f
        box.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawCircle(cx, cy, r, trackPaint)
        val sweep = 360f * fraction
        if (sweep > 0.5f) canvas.drawArc(box, -90f, sweep, false, arcPaint)
        val a = Math.toRadians((-90f + sweep).toDouble())
        canvas.drawCircle(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), stroke * 0.8f, dotPaint)
    }
}
