package com.solarsky.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Sky-Walk-style star map. A dome view of the whole sky:
 * centre = straight up, edge = horizon. It turns with your phone so the
 * top of the map is the direction you are facing, with N / E / S / W around the rim.
 * Tap a planet (on the map or in the list) to get guided to it in the camera.
 */
class SkyMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onSelect: ((String) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val dome = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(12, 20, 52) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * dp; color = Color.argb(90, 150, 180, 255)
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = Color.rgb(120, 255, 160)
    }
    private val cardinal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 255, 160); textSize = 20f * dp; isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val cardinalN = Paint(cardinal).apply { color = Color.rgb(255, 90, 90) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * dp }
    private val starLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255); textSize = 9f * dp
    }
    private val lines = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * dp; color = Color.argb(120, 120, 160, 255)
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 201, 77); textSize = 20f * dp; isFakeBoldText = true
    }
    private val listPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f * dp }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255); textSize = 12f * dp
    }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = Color.rgb(255, 201, 77)
    }
    private val facing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 201, 77) }
    private val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = Color.rgb(255, 120, 200)
    }

    private val hitPoints = ArrayList<Pair<String, FloatArray>>()
    private val listRows = ArrayList<Pair<String, RectF>>()

    private var cx = 0f; private var cy = 0f; private var radius = 0f; private var heading = 0.0

    /** alt/az -> screen. Top of map = heading. Right side = things to your right. */
    private fun toScreen(alt: Double, az: Double): FloatArray {
        val r = ((90.0 - alt) / 90.0 * radius).toFloat()
        val a = (az - heading) * PI / 180.0
        return floatArrayOf((cx + r * sin(a)).toFloat(), (cy - r * cos(a)).toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        heading = SkyState.heading()
        cx = w / 2
        radius = min(w * 0.40f, height * 0.27f)
        cy = 80 * dp + radius + 24 * dp
        hitPoints.clear(); listRows.clear()

        canvas.drawText("★ Sky Map", 16 * dp, 34 * dp, title)
        val loc = if (SkyState.hasRealLocation) String.format("%.2f, %.2f", SkyState.lat, SkyState.lon)
        else "Seoul (default) - allow location for accuracy"
        canvas.drawText("Location: $loc", 16 * dp, 54 * dp, dimPaint)
        canvas.drawText(String.format("Facing %s %.0f°", Astronomy.compassName(heading), heading),
            16 * dp, 72 * dp, dimPaint)

        // dome
        canvas.drawCircle(cx, cy, radius, dome)
        canvas.drawCircle(cx, cy, radius * 2 / 3, ring)   // 30° altitude
        canvas.drawCircle(cx, cy, radius / 3, ring)       // 60° altitude
        canvas.drawCircle(cx, cy, radius, rim)

        // cardinal points around the rim
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        for (i in 0 until 8) {
            val a = (i * 45.0 - heading) * PI / 180.0
            val rr = radius + 18 * dp
            val x = (cx + rr * sin(a)).toFloat()
            val y = (cy - rr * cos(a)).toFloat() + 7 * dp
            val p = if (i == 0) cardinalN else cardinal
            p.textSize = (if (i % 2 == 0) 20f else 13f) * dp
            canvas.drawText(names[i], x, y, p)
        }
        // "you are facing" triangle at the top
        val tri = Path()
        tri.moveTo(cx, cy - radius - 34 * dp)
        tri.lineTo(cx - 8 * dp, cy - radius - 48 * dp)
        tri.lineTo(cx + 8 * dp, cy - radius - 48 * dp)
        tri.close()
        canvas.drawPath(tri, facing)

        val objs = SkyState.objects
        // constellation lines
        val byName = HashMap<String, SkyObject>()
        for (o in objs) if (o.kind == Kind.STAR) byName[o.name] = o
        for ((a, b) in Astronomy.LINES) {
            val sa = byName[a] ?: continue; val sb = byName[b] ?: continue
            if (sa.alt < 0 || sb.alt < 0) continue
            val pa = toScreen(sa.alt, sa.az); val pb = toScreen(sb.alt, sb.az)
            canvas.drawLine(pa[0], pa[1], pb[0], pb[1], lines)
        }
        // objects
        for (o in objs) {
            if (o.alt < 0) continue
            val p = toScreen(o.alt, o.az)
            val baseR: Float = when (o.kind) {
                Kind.SUN -> 9f
                Kind.MOON -> 8f
                Kind.PLANET -> 5f
                Kind.DEEP_SKY -> 4f
                Kind.STAR -> max(1.2f, 3.6f - o.mag.toFloat() * 0.9f)
            }
            val r = baseR * dp
            dot.color = o.color
            if (o.kind == Kind.DEEP_SKY) {
                dot.style = Paint.Style.STROKE; dot.strokeWidth = 1.5f * dp
            } else dot.style = Paint.Style.FILL
            canvas.drawCircle(p[0], p[1], r, dot)
            if (o.kind == Kind.STAR) {
                if (o.mag < 1.0) canvas.drawText(o.name, p[0] + 4 * dp, p[1] - 3 * dp, starLabel)
            } else {
                label.color = o.color
                canvas.drawText(o.name.substringBefore(" ("), p[0] + r + 3 * dp, p[1] + 4 * dp, label)
                hitPoints.add(o.name to p)
            }
            if (o.name == SkyState.selected) canvas.drawCircle(p[0], p[1], r + 8 * dp, selPaint)
        }
        // where the camera points
        if (SkyState.hasOrientation && SkyState.pointingAlt() > 0) {
            val p = toScreen(SkyState.pointingAlt(), SkyState.pointingAz())
            canvas.drawCircle(p[0], p[1], 10 * dp, pointer)
            canvas.drawLine(p[0] - 14 * dp, p[1], p[0] + 14 * dp, p[1], pointer)
            canvas.drawLine(p[0], p[1] - 14 * dp, p[0], p[1] + 14 * dp, pointer)
        }

        // ---------- list of Sun, Moon and planets ----------
        var y = cy + radius + 52 * dp
        canvas.drawText("Tap one to find it with the camera:", 16 * dp, y, dimPaint)
        y += 8 * dp
        val rowH = 30 * dp
        val list = objs.filter { it.kind == Kind.SUN || it.kind == Kind.MOON || it.kind == Kind.PLANET } +
                objs.filter { it.kind == Kind.DEEP_SKY }.sortedByDescending { it.alt }.take(3)
        for (o in list) {
            if (y + rowH > height - 8 * dp) break
            val rect = RectF(8 * dp, y, w - 8 * dp, y + rowH)
            if (o.name == SkyState.selected) canvas.drawRoundRect(rect, 8 * dp, 8 * dp, selPaint)
            dot.style = Paint.Style.FILL; dot.color = o.color
            canvas.drawCircle(24 * dp, y + rowH / 2, 6 * dp, dot)
            listPaint.color = if (o.alt > 0) Color.WHITE else Color.argb(120, 255, 255, 255)
            canvas.drawText(o.name.substringBefore(" ("), 38 * dp, y + rowH / 2 + 5 * dp, listPaint)
            val where = if (o.alt > 0)
                String.format("%s %.0f°  ↑%.0f°", Astronomy.compassName(o.az), o.az, o.alt)
            else "below horizon"
            listPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(where, w - 18 * dp, y + rowH / 2 + 5 * dp, listPaint)
            listPaint.textAlign = Paint.Align.LEFT
            listRows.add(o.name to rect)
            y += rowH
        }
        val sel = SkyState.find(SkyState.selected)
        if (sel != null && y + 20 * dp < height) {
            drawWrapped(canvas, sel.info, 16 * dp, y + 18 * dp, w - 32 * dp)
        }
    }

    private fun drawWrapped(canvas: Canvas, text: String, x: Float, y0: Float, maxW: Float) {
        var y = y0
        var line = ""
        for (word in text.split(" ")) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (dimPaint.measureText(test) > maxW) {
                canvas.drawText(line, x, y, dimPaint); y += 16 * dp; line = word
            } else line = test
        }
        if (line.isNotEmpty()) canvas.drawText(line, x, y, dimPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            for ((name, r) in listRows) {
                if (r.contains(event.x, event.y)) { onSelect?.invoke(name); return true }
            }
            var best: String? = null
            var bestD = 40 * dp
            for ((name, p) in hitPoints) {
                val d = hypot(p[0] - event.x, p[1] - event.y)
                if (d < bestD) { bestD = d; best = name }
            }
            best?.let { onSelect?.invoke(it) }
        }
        return true
    }
}
