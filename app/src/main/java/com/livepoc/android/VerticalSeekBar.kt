package com.livepoc.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Slider vertical desenhado de raiz (o truque de rodar um SeekBar deixava a
 * bola dessincronizada da barra — o thumb interno posiciona-se pela largura
 * pré-rotação). Preenche de baixo para cima; toques/arrasto em qualquer ponto.
 */
class VerticalSeekBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var max = 100
    var progress = 100
        set(value) { field = value.coerceIn(0, max); invalidate() }
    var onUserChanged: ((Int) -> Unit)? = null
    var onUserDone: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7C5CFF.toInt(); strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    private val pad get() = 10 * d          // folga para o thumb não cortar
    private val thumbR get() = 8 * d

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val top = pad
        val bottom = height - pad
        val y = bottom - (bottom - top) * (progress.toFloat() / max)
        c.drawLine(cx, top, cx, bottom, trackPaint)
        c.drawLine(cx, y, cx, bottom, fillPaint)
        c.drawCircle(cx, y, thumbR, thumbPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val top = pad
                val bottom = height - pad
                val f = ((bottom - event.y) / (bottom - top)).coerceIn(0f, 1f)
                val p = (max * f).toInt()
                if (p != progress) {
                    progress = p
                    onUserChanged?.invoke(p)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onUserDone?.invoke(progress)
        }
        return true
    }
}
