package com.solarsky.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * The little picture-in-picture box shown at high zoom (like Galaxy S24 Ultra "Zoom Map").
 * It keeps a wide snapshot taken at low zoom and draws a rectangle showing
 * where the zoomed view is pointing right now, using the motion sensors.
 */
class ZoomMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var snapshot: Bitmap? = null
    private val snapFwd = DoubleArray(3)
    private val snapUp = DoubleArray(3)
    private val snapRight = DoubleArray(3)
    private var snapZoom = 1.0
    var zoom = 1.0

    private val dp = resources.displayMetrics.density
    private val bgPaint = Paint().apply { color = Color.argb(200, 0, 0, 0) }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = Color.argb(200, 255, 255, 255)
    }
    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = Color.rgb(255, 201, 77)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 10f * dp; textAlign = Paint.Align.CENTER
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    fun setSnapshot(bmp: Bitmap, atZoom: Double) {
        snapshot?.recycle()
        snapshot = bmp
        snapZoom = atZoom
        System.arraycopy(SkyState.fwd, 0, snapFwd, 0, 3)
        System.arraycopy(SkyState.up, 0, snapUp, 0, 3)
        System.arraycopy(SkyState.right, 0, snapRight, 0, 3)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        val bmp = snapshot
        if (bmp == null || bmp.isRecycled) {
            canvas.drawText("Zoom out to 1-4x", w / 2, h / 2 - 6 * dp, textPaint)
            canvas.drawText("to build the map", w / 2, h / 2 + 8 * dp, textPaint)
            canvas.drawRect(0f, 0f, w, h, framePaint)
            return
        }
        // fit bitmap into the box
        val s = min(w / bmp.width, h / bmp.height)
        val bw = bmp.width * s; val bh = bmp.height * s
        val ox = (w - bw) / 2; val oy = (h - bh) / 2
        dst.set(ox, oy, ox + bw, oy + bh)
        canvas.drawBitmap(bmp, null, dst, bmpPaint)

        // where are we pointing now, inside the snapshot?
        val f = (bh / 2.0) / Math.tan(Math.toRadians(SkyState.baseFovLong / 2.0)) * snapZoom
        val c = SkyState.toCam(SkyState.fwd, snapRight, snapUp, snapFwd)
        val cx = ox + bw / 2; val cy = oy + bh / 2
        if (c[2] > 0.05) {
            val px = (cx + c[0] / c[2] * f).toFloat()
            val py = (cy - c[1] / c[2] * f).toFloat()
            val rw = max(6f * dp, (bw * snapZoom / zoom).toFloat())
            val rh = max(6f * dp, (bh * snapZoom / zoom).toFloat())
            val inside = px in ox..(ox + bw) && py in oy..(oy + bh)
            if (inside) {
                canvas.drawRect(px - rw / 2, py - rh / 2, px + rw / 2, py + rh / 2, rectPaint)
            } else {
                canvas.drawText("off map", w / 2, h - 6 * dp, textPaint)
            }
        } else {
            canvas.drawText("off map", w / 2, h - 6 * dp, textPaint)
        }
        canvas.drawRect(0f, 0f, w, h, framePaint)
    }
}
