package com.livepoc.android

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Aspect-true container for the video surfaces: the largest rectangle with the
 * CONTENT's aspect ratio (set via setAspect — camera preview orientation or
 * the stream's real coded size) that fits the available space (letterbox).
 * A SurfaceView stretches its buffer to the view, so matching the view to the
 * content is what prevents distortion.
 */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var aspect = 16f / 9f

    fun setAspect(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val r = w.toFloat() / h
        if (kotlin.math.abs(r - aspect) > 0.001f) {
            aspect = r
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        var height = (width / aspect).toInt()
        if (maxH in 1 until height) {
            height = maxH
            width = (height * aspect).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}
