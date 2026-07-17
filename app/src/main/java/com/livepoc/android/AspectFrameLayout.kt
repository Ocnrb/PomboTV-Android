package com.livepoc.android

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Aspect-true container for the video surfaces. Diferente do padrão "medir-se
 * mais pequeno e deixar o pai centrar": aqui o container PREENCHE o espaço
 * disponível e mede+posiciona os filhos ao tamanho do CONTEÚDO (letterbox no
 * FIT, cover no FILL), centrados. Uma SurfaceView estica o buffer à sua view,
 * por isso o que evita distorção é dar À SURFACEVIEW o tamanho com o aspect
 * certo — e este container fá-lo diretamente, sem depender do pai (que não
 * respeitava a altura menor quando o conteúdo era landscape → esticava).
 */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var aspect = 16f / 9f

    /** FILL (cover): o conteúdo TAPA o espaço, o excesso é clipado (o container
     *  mantém clipChildren). FIT (false) = letterbox (barras à volta). */
    var fillMode = false
        set(v) { if (field != v) { field = v; requestLayout() } }

    init { clipChildren = true }

    fun setAspect(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val r = w.toFloat() / h
        if (kotlin.math.abs(r - aspect) > 0.001f) { aspect = r; requestLayout() }
    }

    /** Tamanho do conteúdo (largura, altura) com o aspect certo dentro do espaço. */
    private fun contentSize(availW: Int, availH: Int): Pair<Int, Int> {
        if (availW <= 0 || availH <= 0) return Pair(maxOf(availW, 0), maxOf(availH, 0))
        var cw = availW
        var ch = (cw / aspect).toInt()
        if (fillMode) {
            if (ch < availH) { ch = availH; cw = (ch * aspect).toInt() } // cover
        } else {
            if (ch > availH) { ch = availH; cw = (ch * aspect).toInt() } // letterbox
        }
        return Pair(cw, ch)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val availH = MeasureSpec.getSize(heightMeasureSpec)
        val (cw, ch) = contentSize(availW, availH)
        val cwSpec = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY)
        val chSpec = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cwSpec, chSpec)
        // o container ocupa TODO o espaço que lhe deram (o letterbox é interno)
        setMeasuredDimension(
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) cw else availW,
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) ch else availH
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pw = r - l; val ph = b - t
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val cw = c.measuredWidth; val ch = c.measuredHeight
            val cl = (pw - cw) / 2; val ct = (ph - ch) / 2 // centrado (pode ser <0 no cover)
            c.layout(cl, ct, cl + cw, ct + ch)
        }
    }
}
