package com.solarsky.camera

import android.graphics.Color
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class Kind { SUN, MOON, PLANET, STAR, DEEP_SKY }

data class SkyObject(
    val name: String,
    val kind: Kind,
    val alt: Double,      // degrees above horizon
    val az: Double,       // degrees from North, clockwise (E = 90)
    val mag: Double,
    val color: Int,
    val info: String
)

/**
 * Small, self-contained astronomy engine.
 * Planets: JPL "Keplerian elements for approximate positions" (1800-2050).
 * Moon: low-precision series (~0.5 degree).
 * Good enough to point a phone camera at things.
 */
object Astronomy {
    private const val DEG = PI / 180.0
    private const val OBLIQUITY = 23.43928

    fun julianDay(ms: Long): Double = ms / 86400000.0 + 2440587.5

    fun norm360(x: Double): Double {
        val r = x % 360.0
        return if (r < 0) r + 360.0 else r
    }

    fun lstDeg(jd: Double, lonDeg: Double): Double {
        val d = jd - 2451545.0
        return norm360(280.46061837 + 360.98564736629 * d + lonDeg)
    }

    /** returns (alt, az) in degrees */
    fun raDecToAltAz(raDeg: Double, decDeg: Double, latDeg: Double, lst: Double): DoubleArray {
        val h = (lst - raDeg) * DEG
        val d = decDeg * DEG
        val p = latDeg * DEG
        val sinAlt = (sin(d) * sin(p) + cos(d) * cos(p) * cos(h)).coerceIn(-1.0, 1.0)
        val alt = asin(sinAlt)
        val y = -sin(h) * cos(d)
        val x = sin(d) * cos(p) - cos(d) * sin(p) * cos(h)
        val az = atan2(y, x)
        return doubleArrayOf(alt / DEG, norm360(az / DEG))
    }

    /** ecliptic rectangular -> (ra, dec, distance) */
    private fun eclToRaDec(x: Double, y: Double, z: Double): DoubleArray {
        val e = OBLIQUITY * DEG
        val xe = x
        val ye = y * cos(e) - z * sin(e)
        val ze = y * sin(e) + z * cos(e)
        val ra = norm360(atan2(ye, xe) / DEG)
        val dist = sqrt(xe * xe + ye * ye + ze * ze)
        val dec = atan2(ze, sqrt(xe * xe + ye * ye)) / DEG
        return doubleArrayOf(ra, dec, dist)
    }

    private class El(
        val a0: Double, val aR: Double, val e0: Double, val eR: Double,
        val i0: Double, val iR: Double, val l0: Double, val lR: Double,
        val w0: Double, val wR: Double, val o0: Double, val oR: Double
    )

    private val EARTH = El(1.00000261, 0.00000562, 0.01671123, -0.00004392, -0.00001531, -0.01294668,
        100.46457166, 35999.37244981, 102.93768193, 0.32327364, 0.0, 0.0)

    private class PlanetDef(val name: String, val el: El, val mag: Double, val color: Int, val tip: String)

    private val PLANETS = listOf(
        PlanetDef("Mercury", El(0.38709927, 0.00000037, 0.20563593, 0.00001906, 7.00497902, -0.00594749,
            252.25032350, 149472.67411175, 77.45779628, 0.16047689, 48.33076593, -0.12534081),
            0.0, Color.rgb(190, 190, 190), "Low near sunrise/sunset. Use 10-30x."),
        PlanetDef("Venus", El(0.72333566, 0.00000390, 0.00677672, -0.00004107, 3.39467605, -0.00078890,
            181.97909950, 58517.81538729, 131.60246718, 0.00268329, 76.67984255, -0.27769418),
            -4.2, Color.rgb(255, 245, 200), "Brightest 'star'. At 30-100x you may see its crescent phase. Lower EV."),
        PlanetDef("Mars", El(1.52371034, 0.00001847, 0.09339410, 0.00007882, 1.84969142, -0.00813131,
            -4.55343205, 19140.30268499, -23.94362959, 0.44441088, 49.55953891, -0.29257343),
            0.5, Color.rgb(255, 110, 70), "Orange dot. Tiny even at max zoom."),
        PlanetDef("Jupiter", El(5.20288700, -0.00011607, 0.04838624, -0.00013253, 1.30439695, -0.00183714,
            34.39644051, 3034.74612775, 14.72847983, 0.21252668, 100.47390909, 0.20469106),
            -2.3, Color.rgb(240, 210, 160), "At 30-100x on a tripod: a small disc with up to 4 moons as dots."),
        PlanetDef("Saturn", El(9.53667594, -0.00125060, 0.05386179, -0.00050991, 2.48599187, 0.00193609,
            49.95424423, 1222.49362201, 92.59887831, -0.41897216, 113.66242448, -0.28867794),
            0.6, Color.rgb(255, 215, 120), "At ~100x, very steady: an oval blob hinting at the rings."),
        PlanetDef("Uranus", El(19.18916464, -0.00196176, 0.04725744, -0.00004397, 0.77263783, -0.00242939,
            313.23810451, 428.48202785, 170.95427630, 0.40805281, 74.01692503, 0.04240589),
            5.7, Color.rgb(150, 230, 240), "Very faint. Needs dark sky; looks like a star."),
        PlanetDef("Neptune", El(30.06992276, 0.00026291, 0.00859048, 0.00005105, 1.77004347, 0.00035372,
            -55.12002969, 218.45945325, 44.96476227, -0.32241464, 131.78422574, -0.00508664),
            7.8, Color.rgb(110, 140, 255), "Too faint for most phones.")
    )

    private fun helio(el: El, t: Double): DoubleArray {
        val a = el.a0 + el.aR * t
        val e = el.e0 + el.eR * t
        val inc = (el.i0 + el.iR * t) * DEG
        val l = el.l0 + el.lR * t
        val wBar = el.w0 + el.wR * t
        val node = el.o0 + el.oR * t
        val w = (wBar - node) * DEG
        val o = node * DEG
        var m = norm360(l - wBar)
        if (m > 180) m -= 360.0
        val mr = m * DEG
        var ea = mr + e * sin(mr)
        repeat(12) { ea -= (ea - e * sin(ea) - mr) / (1 - e * cos(ea)) }
        val xp = a * (cos(ea) - e)
        val yp = a * sqrt(1 - e * e) * sin(ea)
        val cw = cos(w); val sw = sin(w)
        val co = cos(o); val so = sin(o)
        val ci = cos(inc); val si = sin(inc)
        val x = (cw * co - sw * so * ci) * xp + (-sw * co - cw * so * ci) * yp
        val y = (cw * so + sw * co * ci) * xp + (-sw * so + cw * co * ci) * yp
        val z = (sw * si) * xp + (cw * si) * yp
        return doubleArrayOf(x, y, z)
    }

    // ---------- Stars: name, RA deg, Dec deg, magnitude ----------
    class Star(val name: String, val ra: Double, val dec: Double, val mag: Double)

    val STARS = listOf(
        Star("Sirius", 101.287, -16.716, -1.46), Star("Canopus", 95.988, -52.696, -0.74),
        Star("Rigil Kent", 219.902, -60.834, -0.27), Star("Arcturus", 213.915, 19.182, -0.05),
        Star("Vega", 279.234, 38.784, 0.03), Star("Capella", 79.172, 45.998, 0.08),
        Star("Rigel", 78.634, -8.202, 0.13), Star("Procyon", 114.825, 5.225, 0.34),
        Star("Achernar", 24.429, -57.237, 0.46), Star("Betelgeuse", 88.793, 7.407, 0.50),
        Star("Hadar", 210.956, -60.373, 0.61), Star("Altair", 297.696, 8.868, 0.77),
        Star("Acrux", 186.650, -63.099, 0.76), Star("Aldebaran", 68.980, 16.509, 0.85),
        Star("Spica", 201.298, -11.161, 0.97), Star("Antares", 247.352, -26.432, 1.06),
        Star("Pollux", 116.329, 28.026, 1.14), Star("Fomalhaut", 344.413, -29.622, 1.16),
        Star("Deneb", 310.358, 45.280, 1.25), Star("Mimosa", 191.930, -59.689, 1.25),
        Star("Regulus", 152.093, 11.967, 1.35), Star("Castor", 113.650, 31.888, 1.58),
        Star("Shaula", 263.402, -37.104, 1.62), Star("Bellatrix", 81.283, 6.350, 1.64),
        Star("Alnilam", 84.053, -1.202, 1.69), Star("Alnitak", 85.190, -1.943, 1.77),
        Star("Alioth", 193.507, 55.960, 1.77), Star("Dubhe", 165.932, 61.751, 1.79),
        Star("Mirfak", 51.081, 49.861, 1.79), Star("Alkaid", 206.885, 49.313, 1.86),
        Star("Polaris", 37.955, 89.264, 1.98), Star("Hamal", 31.793, 23.462, 2.00),
        Star("Saiph", 86.939, -9.670, 2.06), Star("Alpheratz", 2.097, 29.091, 2.06),
        Star("Algol", 47.042, 40.956, 2.10), Star("Denebola", 177.265, 14.572, 2.14),
        Star("Mintaka", 83.002, -0.299, 2.23), Star("Mizar", 200.981, 54.925, 2.23),
        Star("Sadr", 305.557, 40.257, 2.23), Star("Schedar", 10.127, 56.537, 2.24),
        Star("Caph", 2.295, 59.150, 2.28), Star("Merak", 165.460, 56.382, 2.37),
        Star("Phecda", 178.458, 53.695, 2.44), Star("Navi", 14.177, 60.717, 2.47),
        Star("Ruchbah", 21.454, 60.235, 2.68), Star("Megrez", 183.857, 57.033, 3.31),
        Star("Segin", 28.599, 63.670, 3.37), Star("Albireo", 292.680, 27.960, 3.05),
        Star("Algieba", 154.993, 19.842, 2.08), Star("Kaus Australis", 276.043, -34.385, 1.85),
        Star("Nunki", 283.816, -26.297, 2.05)
    )

    /** Constellation stick figures (pairs of star names) */
    val LINES = listOf(
        // Orion
        "Betelgeuse" to "Bellatrix", "Bellatrix" to "Mintaka", "Mintaka" to "Alnilam",
        "Alnilam" to "Alnitak", "Alnitak" to "Saiph", "Saiph" to "Rigel", "Rigel" to "Mintaka",
        "Betelgeuse" to "Alnitak",
        // Big Dipper
        "Dubhe" to "Merak", "Merak" to "Phecda", "Phecda" to "Megrez", "Megrez" to "Dubhe",
        "Megrez" to "Alioth", "Alioth" to "Mizar", "Mizar" to "Alkaid",
        // Cassiopeia
        "Caph" to "Schedar", "Schedar" to "Navi", "Navi" to "Ruchbah", "Ruchbah" to "Segin",
        // Summer Triangle + Cygnus
        "Vega" to "Deneb", "Deneb" to "Altair", "Altair" to "Vega",
        "Deneb" to "Sadr", "Sadr" to "Albireo",
        // Gemini, Leo
        "Castor" to "Pollux", "Regulus" to "Algieba", "Algieba" to "Denebola", "Denebola" to "Regulus",
        // Southern Cross
        "Acrux" to "Mimosa",
        // Sagittarius-ish
        "Kaus Australis" to "Nunki"
    )

    private class Dso(val name: String, val ra: Double, val dec: Double, val mag: Double, val tip: String)

    private val DEEP_SKY = listOf(
        Dso("Andromeda Galaxy (M31)", 10.685, 41.269, 3.4,
            "2.5 million light-years away. Needs a dark sky + tripod + Pro/Night mode long exposure, not zoom."),
        Dso("Orion Nebula (M42)", 83.822, -5.391, 4.0, "Try 3-10x on a tripod with night mode."),
        Dso("Pleiades (M45)", 56.750, 24.117, 1.6, "Star cluster. Best at 2-5x."),
        Dso("Milky Way Center", 266.417, -29.008, 4.5, "Needs a very dark sky and long exposure at 1x."),
        Dso("Triangulum Galaxy (M33)", 23.462, 30.660, 5.7, "Very faint. Long exposure only."),
        Dso("Whirlpool Galaxy (M51)", 202.470, 47.195, 8.4, "Too faint for phones; telescope needed."),
        Dso("Large Magellanic Cloud", 80.894, -69.756, 0.9, "Southern hemisphere galaxy. Visible at 1x."),
        Dso("Lagoon Nebula (M8)", 270.904, -24.387, 6.0, "Long exposure on a tripod.")
    )

    /** Compute everything currently in the sky for a location and time. */
    fun computeAll(lat: Double, lon: Double, ms: Long): List<SkyObject> {
        val jd = julianDay(ms)
        val d = jd - 2451545.0
        val t = d / 36525.0
        val lst = lstDeg(jd, lon)
        val out = ArrayList<SkyObject>()

        val earth = helio(EARTH, t)

        // Sun (geocentric = -earth)
        val sunEq = eclToRaDec(-earth[0], -earth[1], -earth[2])
        val sunAA = raDecToAltAz(sunEq[0], sunEq[1], lat, lst)
        out.add(SkyObject("Sun", Kind.SUN, sunAA[0], sunAA[1], -26.7, Color.rgb(255, 210, 60),
            "NEVER point the zoom at the Sun - it can damage the camera and your eyes."))
        val sunLon = norm360(atan2(-earth[1], -earth[0]) / DEG)

        // Moon
        val mL = 218.316 + 13.176396 * d
        val mM = (134.963 + 13.064993 * d) * DEG
        val mF = (93.272 + 13.229350 * d) * DEG
        val moonLon = norm360(mL + 6.289 * sin(mM))
        val moonLat = 5.128 * sin(mF)
        val ml = moonLon * DEG
        val mb = moonLat * DEG
        val moonEq = eclToRaDec(cos(mb) * cos(ml), cos(mb) * sin(ml), sin(mb))
        val moonAA = raDecToAltAz(moonEq[0], moonEq[1], lat, lst)
        val elong = (moonLon - sunLon) * DEG
        val lit = ((1 - cos(elong)) / 2 * 100).toInt()
        out.add(SkyObject("Moon", Kind.MOON, moonAA[0], moonAA[1], -12.0, Color.rgb(235, 235, 220),
            "$lit% lit. Best zoom target! Try 30-100x and lower EV (☀−)."))

        // Planets
        for (p in PLANETS) {
            val h = helio(p.el, t)
            val gx = h[0] - earth[0]; val gy = h[1] - earth[1]; val gz = h[2] - earth[2]
            val eq = eclToRaDec(gx, gy, gz)
            val aa = raDecToAltAz(eq[0], eq[1], lat, lst)
            out.add(SkyObject(p.name, Kind.PLANET, aa[0], aa[1], p.mag, p.color,
                String.format("%.2f AU away. %s", eq[2], p.tip)))
        }

        // Deep sky
        for (o in DEEP_SKY) {
            val aa = raDecToAltAz(o.ra, o.dec, lat, lst)
            out.add(SkyObject(o.name, Kind.DEEP_SKY, aa[0], aa[1], o.mag, Color.rgb(200, 150, 255), o.tip))
        }

        // Stars
        for (s in STARS) {
            val aa = raDecToAltAz(s.ra, s.dec, lat, lst)
            out.add(SkyObject(s.name, Kind.STAR, aa[0], aa[1], s.mag, Color.WHITE, "Star, magnitude ${s.mag}"))
        }
        return out
    }

    fun compassName(az: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[((norm360(az) + 22.5) / 45.0).toInt() % 8]
    }
}
