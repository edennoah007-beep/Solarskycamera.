package com.solarsky.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Shared live state: where the phone is, where it points, what is in the sky.
 * World frame: x = East, y = North (true), z = Up.
 */
object SkyState {
    private const val DEG = PI / 180.0

    var lat = 37.5665          // fallback: Seoul until GPS/network location arrives
    var lon = 126.9780
    var hasRealLocation = false

    // Camera axes in world coordinates
    val fwd = doubleArrayOf(0.0, 1.0, 0.0)
    val up = doubleArrayOf(0.0, 0.0, 1.0)
    val right = doubleArrayOf(1.0, 0.0, 0.0)
    var hasOrientation = false

    /** Long-side field of view of the main camera at 1x (degrees). Updated from the real lens. */
    var baseFovLong = 68.0

    var objects: List<SkyObject> = emptyList()
    var selected: String? = null

    fun vec(altDeg: Double, azDeg: Double): DoubleArray {
        val a = altDeg * DEG
        val z = azDeg * DEG
        return doubleArrayOf(cos(a) * sin(z), cos(a) * cos(z), sin(a))
    }

    fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun pointingAlt(): Double = asin(fwd[2].coerceIn(-1.0, 1.0)) / DEG
    fun pointingAz(): Double = Astronomy.norm360(atan2(fwd[0], fwd[1]) / DEG)

    /** Heading for the flat sky map: camera direction, or phone top when lying flat. */
    fun heading(): Double {
        return if (abs(fwd[2]) > 0.8) Astronomy.norm360(atan2(up[0], up[1]) / DEG)
        else Astronomy.norm360(atan2(fwd[0], fwd[1]) / DEG)
    }

    /** focal length in screen pixels for a view of height h at given zoom */
    fun focalPx(viewH: Int, zoom: Double): Double =
        (viewH / 2.0) / tan(baseFovLong / 2.0 * DEG) * zoom

    /**
     * Camera-space coordinates of a world vector: (x right, y up, z forward).
     */
    fun toCam(v: DoubleArray, r: DoubleArray = right, u: DoubleArray = up, f: DoubleArray = fwd) =
        doubleArrayOf(dot(v, r), dot(v, u), dot(v, f))

    fun find(name: String?): SkyObject? = objects.firstOrNull { it.name == name }
}
