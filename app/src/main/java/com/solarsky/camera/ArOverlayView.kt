package com.solarsky.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws on top of the camera: the target box, AR labels for planets/stars,
 * the horizon with N/E/S/W, a guide arrow to the selected target,
 * and the "hold steady" meter.
 */
class ArOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var zoom = 1.0
    var shake = 0f
    var showLabels = true
    var bigMessage: String? = null
        set(value) { field = value; invalidate() }

    private val dp = resources.displayMetrics.density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = Color.WHITE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 13f * dp
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255); textSize = 11f * dp
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = Color.argb(160, 120, 255, 160)
    }
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 255, 160); textSize = 20f * dp; isFakeBoldText = true
        textAlign = Paint.Align.CENTER; setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.rgb(255, 201, 77)
    }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 90f * dp; textAlign = Paint.Align.CENTER
        isFakeBoldText = true; setShadowLayer(10f, 0f, 0f, Color.BLACK)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * dp; color = Color.argb(110, 140, 180, 255)
    }

    /** name of the object currently inside the target box, if any */
    var objectInBox: String? = null
        private set

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width; val h = height
        val cx = w / 2f; val cy = h / 2f
        val f = SkyState.focalPx(h, zoom)

        // ---------- AR layer ----------
        objectInBox = null
        val boxHalf = min(w, h) * 0.16f
        if (showLabels && SkyState.hasOrientation) {
            drawHorizon(canvas, cx, cy, f)
            drawConstellations(canvas, cx, cy, f)
            var bestDist = Float.MAX_VALUE
            for (o in SkyState.objects) {
                if (o.kind == Kind.STAR && o.mag > 2.6 && zoom < 3) continue
                val c = SkyState.toCam(SkyState.vec(o.alt, o.az))
                if (c[2] <= 0.02) continue
                val px = (cx + c[0] / c[2] * f).toFloat()
                val py = (cy - c[1] / c[2] * f).toFloat()
                if (px < -50 || px > w + 50 || py < -50 || py > h + 50) continue
                val below = o.alt < 0
                val alpha = if (below) 90 else 255
                val baseR: Float = when (o.kind) {
                    Kind.SUN, Kind.MOON -> 9f
                    Kind.PLANET -> 6f
                    Kind.DEEP_SKY -> 7f
                    Kind.STAR -> max(1.5f, 4.5f - o.mag.toFloat())
                }
                val r = baseR * dp
                dotPaint.color = o.color; dotPaint.alpha = alpha
                if (o.kind == Kind.DEEP_SKY) {
                    ringPaint.color = o.color; ringPaint.alpha = alpha
                    canvas.drawCircle(px, py, r, ringPaint)
                } else canvas.drawCircle(px, py, r, dotPaint)
                if (o.kind != Kind.STAR || o.mag < 1.6 || zoom >= 3) {
                    val p = if (o.kind == Kind.STAR) smallText else textPaint
                    p.alpha = alpha
                    canvas.drawText(o.name + if (below) " (below horizon)" else "", px + r + 4 * dp, py + 4 * dp, p)
                    p.alpha = 255
                }
                if (o.kind != Kind.STAR || zoom > 5) {
                    val d = hypot(px - cx, py - cy)
                    if (px > cx - boxHalf && px < cx + boxHalf && py > cy - boxHalf && py < cy + boxHalf && d < bestDist) {
                        bestDist = d; objectInBox = o.name
                    }
                }
            }
            drawTargetArrow(canvas, cx, cy, f, boxHalf)
        }

        // ---------- target box (the "box" that helps you aim) ----------
        boxPaint.color = if (objectInBox != null) Color.rgb(80, 255, 120) else Color.WHITE
        val l = cx - boxHalf; val t = cy - boxHalf; val rr = cx + boxHalf; val b = cy + boxHalf
        val c = boxHalf * 0.35f
        canvas.drawLine(l, t, l + c, t, boxPaint); canvas.drawLine(l, t, l, t + c, boxPaint)
        canvas.drawLine(rr, t, rr - c, t, boxPaint); canvas.drawLine(rr, t, rr, t + c, boxPaint)
        canvas.drawLine(l, b, l + c, b, boxPaint); canvas.drawLine(l, b, l, b - c, boxPaint)
        canvas.drawLine(rr, b, rr - c, b, boxPaint); canvas.drawLine(rr, b, rr, b - c, boxPaint)
        canvas.drawLine(cx - 8 * dp, cy, cx + 8 * dp, cy, boxPaint)
        canvas.drawLine(cx, cy - 8 * dp, cx, cy + 8 * dp, boxPaint)
        objectInBox?.let {
            textPaint.color = Color.rgb(80, 255, 120)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("◎ $it in frame", cx, t - 10 * dp, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = Color.WHITE
        }

        // ---------- pointing info + steadiness ----------
        if (SkyState.hasOrientation) {
            val az = SkyState.pointingAz(); val alt = SkyState.pointingAlt()
            smallText.textAlign = Paint.Align.CENTER
            canvas.drawText(
                String.format("Facing %s %.0f°   Up %.0f°", Astronomy.compassName(az), az, alt),
                cx, b + 22 * dp, smallText
            )
            smallText.textAlign = Paint.Align.LEFT
        }
        if (zoom >= 8) {
            val steady = shake < 0.03f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = if (steady) Color.rgb(80, 255, 120) else Color.rgb(255, 170, 60)
            canvas.drawText(
                if (steady) "✓ Steady - good time to shoot" else "✋ Hold steady / use a tripod",
                cx, b + 42 * dp, textPaint
            )
            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.LEFT
        }

        bigMessage?.let { canvas.drawText(it, cx, cy + 30 * dp, bigPaint) }
    }

    private fun project(v: DoubleArray, cx: Float, cy: Float, f: Double): FloatArray? {
        val c = SkyState.toCam(v)
        if (c[2] <= 0.02) return null
        return floatArrayOf((cx + c[0] / c[2] * f).toFloat(), (cy - c[1] / c[2] * f).toFloat())
    }

    private fun drawHorizon(canvas: Canvas, cx: Float, cy: Float, f: Double) {
        val path = Path()
        var started = false
        val step = if (zoom > 20) 0.25 else if (zoom > 4) 1.0 else 3.0
        var az = 0.0
        while (az <= 360.0) {
            val p = project(SkyState.vec(0.0, az), cx, cy, f)
            if (p != null && p[0] > -2000 && p[0] < width + 2000 && p[1] > -2000 && p[1] < height + 2000) {
                if (!started) { path.moveTo(p[0], p[1]); started = true } else path.lineTo(p[0], p[1])
            } else started = false
            az += step
        }
        canvas.drawPath(path, horizonPaint)
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        for (i in 0 until 8) {
            val p = project(SkyState.vec(0.0, i * 45.0), cx, cy, f) ?: continue
            cardinalPaint.textSize = (if (i % 2 == 0) 22f else 15f) * dp
            canvas.drawText(names[i], p[0], p[1] - 6 * dp, cardinalPaint)
        }
    }

    private fun drawConstellations(canvas: Canvas, cx: Float, cy: Float, f: Double) {
        if (zoom > 6) return
        val byName = HashMap<String, SkyObject>()
        for (o in SkyState.objects) if (o.kind == Kind.STAR) byName[o.name] = o
        for ((a, b) in Astronomy.LINES) {
            val sa = byName[a] ?: continue; val sb = byName[b] ?: continue
            val pa = project(SkyState.vec(sa.alt, sa.az), cx, cy, f) ?: continue
            val pb = project(SkyState.vec(sb.alt, sb.az), cx, cy, f) ?: continue
            canvas.drawLine(pa[0], pa[1], pb[0], pb[1], linePaint)
        }
    }

    /** Arrow from the centre toward the selected target (like Sky Walk's guide). */
    private fun drawTargetArrow(canvas: Canvas, cx: Float, cy: Float, f: Double, boxHalf: Float) {
        val target = SkyState.find(SkyState.selected) ?: return
        val c = SkyState.toCam(SkyState.vec(target.alt, target.az))
        var onScreen = false
        if (c[2] > 0.02) {
            val px = cx + c[0] / c[2] * f; val py = cy - c[1] / c[2] * f
            onScreen = px > cx - boxHalf && px < cx + boxHalf && py > cy - boxHalf && py < cy + boxHalf
        }
        textPaint.textAlign = Paint.Align.CENTER
        if (onScreen) {
            textPaint.color = Color.rgb(255, 201, 77)
            canvas.drawText("🎯 ${target.name} locked - take the shot!", cx, 96 * dp, textPaint)
        } else {
            val ang = atan2(-c[1], c[0]).toFloat()  // screen angle
            val r = min(width, height) * 0.30f
            val ax = cx + cos(ang) * r; val ay = cy + sin(ang) * r
            val s = 16 * dp
            val path = Path()
            path.moveTo(ax + cos(ang) * s, ay + sin(ang) * s)
            path.lineTo(ax + cos(ang + 2.5f) * s, ay + sin(ang + 2.5f) * s)
            path.lineTo(ax + cos(ang - 2.5f) * s, ay + sin(ang - 2.5f) * s)
            path.close()
            canvas.drawPath(path, arrowPaint)
            textPaint.color = Color.rgb(255, 201, 77)
            canvas.drawText(
                String.format("Find %s: %s %.0f°, %.0f° up", target.name,
                    Astronomy.compassName(target.az), target.az, target.alt),
                cx, 96 * dp, textPaint
            )
            if (target.alt < 0) {
                textPaint.color = Color.rgb(255, 120, 120)
                canvas.drawText("(below the horizon right now)", cx, 116 * dp, textPaint)
            }
        }
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.LEFT
    }
}
