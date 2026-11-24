package io.lampyr.glasses.render

import kotlin.math.*

// Launcher glyph for the assistant app (app id "joy"): a neutral orb face, not tied to any avatar pack.
const val ASSISTANT_LAUNCHER_ICON = "assistant-orb"

val ANIMATED_ICONS =
    setOf(
        "beeper",
        "media",
        "t3",
        "bell",
        "health",
        "prompter",
        "status",
        "settings",
        "book",
        "sun",
        "spin",
        "clock",
        "printer",
        "gauge",
    )

fun points(vararg xy: Number): List<Pair<Double, Double>> =
    xy.toList().chunked(2).map { it[0].toDouble() to it[1].toDouble() }

fun Gfx.icon(n: String, xn: Number, yn: Number, sn: Number, t: Double = 0.0, tone: Int = this.tone) = withTone(tone) { iconShape(n, xn, yn, sn, t) }

private fun Gfx.iconShape(n: String, xn: Number, yn: Number, sn: Number, t: Double = 0.0) {
    val x = xn.toDouble()
    val y = yn.toDouble()
    val s = sn.toDouble()
    val l = if (s == 72.0) 6.0 else max(2.0, floor(s / 14 + .5))
    val u = s / 24
    fun pts(vararg xy: Number) =
        xy.toList().chunked(2).map { x + it[0].toDouble() * u to y + it[1].toDouble() * u }
    when (if (n == "joy") ASSISTANT_LAUNCHER_ICON else n) {
        "beeper" -> {
            p.rr(x + 2 * u, y + 3 * u, 20 * u, 14 * u, 4 * u, l)
            p.poly(pts(6, 16, 5, 22, 11, 16))
            for (i in 0..2) {
                val b = if (sin(t * 6 - i * .9) > .3) -1.5 * u else 0.0
                p.circ(x + (7 + i * 5) * u, y + 10 * u + b, 1.6 * u, 1, true)
            }
        }
        "media" -> {
            for (i in 0..4) {
                val h = (.35 + .6 * abs(sin(t * 3 + i * 1.3))) * 16 * u
                p.fr(x + (2 + i * 4.4) * u, y + 21 * u - h, 3 * u, h)
            }
            p.fr(x + u, y + 22 * u, 22 * u, max(2.0, u))
        }
        "note" -> {
            p.circ(x + 7 * u, y + 18 * u, 3.2 * u, l, true)
            p.circ(x + 17 * u, y + 16 * u, 3.2 * u, l, true)
            p.ln(x + 10 * u, y + 18 * u, x + 10 * u, y + 5 * u, l)
            p.ln(x + 20 * u, y + 16 * u, x + 20 * u, y + 3 * u, l)
            p.poly(pts(10, 5, 20, 3, 20, 7, 10, 9))
        }
        "t3" -> {
            p.rr(x + u, y + 3 * u, 22 * u, 18 * u, 3 * u, l)
            p.ln(x + u, y + 8 * u, x + 23 * u, y + 8 * u, l)
            p.path(pts(5, 11, 9, 14, 5, 17), l)
            if (floor(t * 2).toInt() % 2 == 0)
                p.fr(x + 11 * u, y + 16 * u, 6 * u, max(2.0, 1.6 * u))
        }
        "calendar" -> {
            p.rr(x + 2 * u, y + 4 * u, 20 * u, 18 * u, 3 * u, l)
            p.fr(x + 2 * u, y + 4 * u, 20 * u, 5 * u)
            p.ln(x + 7 * u, y + 2 * u, x + 7 * u, y + 6 * u, l)
            p.ln(x + 17 * u, y + 2 * u, x + 17 * u, y + 6 * u, l)
            for (r in 0..2) for (c in 0..3) p.fr(
                x + (5 + c * 4) * u,
                y + (11 + r * 3.5) * u,
                2 * u,
                2 * u,
            )
        }
        "bell" -> {
            save()
            translate(x + 12 * u, y + 3 * u)
            rotate(sin(t * 5) * .12)
            p.lw(l)
            beginPath()
            moveTo(-8 * u, 15 * u)
            quadraticCurveTo(-7 * u, 2 * u, 0, 2 * u)
            quadraticCurveTo(7 * u, 2 * u, 8 * u, 15 * u)
            lineTo(9 * u, 17 * u)
            lineTo(-9 * u, 17 * u)
            closePath()
            stroke()
            p.circ(0, 19.5 * u, 2 * u, 1, true)
            p.circ(0, .5 * u, 1.4 * u, 1, true)
            restore()
        }
        "people" -> {
            p.circ(x + 8 * u, y + 8 * u, 3.6 * u, l)
            p.arc(x + 8 * u, y + 21 * u, 7 * u, PI, 2 * PI, l)
            p.circ(x + 17 * u, y + 7 * u, 3.2 * u, l, true)
            p.poly(pts(11.5, 21, 12.5, 13, 21.5, 13, 22.5, 21))
        }
        "health" -> {
            p.heart(x + 12 * u, y + 12 * u, 22 * u, false, l)
            p.path(pts(2, 13, 7, 13, 9, 8, 12, 18, 14, 11, 16, 13, 22, 13), l)
            p.circ(x + (2 + 20 * ((t * 1.2) % 1)) * u, y + 13 * u, 1.6 * u, 1, true)
        }
        "home" -> {
            p.path(pts(2, 12, 12, 3, 22, 12), l)
            p.rect(x + 5 * u, y + 11 * u, 14 * u, 11 * u, l)
            p.fr(x + 10 * u, y + 15 * u, 4 * u, 7 * u)
        }
        "docs" -> {
            p.path(pts(5, 2, 15, 2, 20, 7, 20, 22, 5, 22, 5, 2), l)
            p.path(pts(15, 2, 15, 7, 20, 7), l)
            for (r in listOf(11, 14, 17)) p.ln(
                x + 8 * u,
                y + r * u,
                x + 17 * u,
                y + r * u,
                max(1.5, l * .7),
            )
        }
        "prompter" -> {
            p.rr(x + 2 * u, y + 3 * u, 20 * u, 15 * u, 2 * u, l)
            val o = (t * 6) % 3
            for (i in 0..3) {
                val yy = 6 + i * 3 - o
                if (yy > 4 && yy < 16)
                    p.ln(
                        x + 5 * u,
                        y + yy * u,
                        x + (if (i % 2 == 1) 15 else 19) * u,
                        y + yy * u,
                        max(1.5, l * .7),
                    )
            }
            p.ln(x + 12 * u, y + 18 * u, x + 12 * u, y + 22 * u, l)
            p.ln(x + 7 * u, y + 22 * u, x + 17 * u, y + 22 * u, l)
        }
        "mic" -> {
            p.rr(x + 8.5 * u, y + 2 * u, 7 * u, 12 * u, 3.5 * u, l, true)
            p.arc(x + 12 * u, y + 10 * u, 6 * u, 0, PI, l)
            p.ln(x + 12 * u, y + 16 * u, x + 12 * u, y + 21 * u, l)
            p.ln(x + 8 * u, y + 21 * u, x + 16 * u, y + 21 * u, l)
            if (t < 0) {
                val r = (abs(t) * 1.5) % 1
                p.arc(x + 12 * u, y + 8 * u, (9 + r * 4) * u, -.6, .6, 1.5)
                p.arc(x + 12 * u, y + 8 * u, (9 + r * 4) * u, PI - .6, PI + .6, 1.5)
            }
        }
        "status" -> {
            p.rr(x + 2 * u, y + 7 * u, 18 * u, 10 * u, 2 * u, l)
            p.fr(x + 20 * u, y + 10 * u, 2.5 * u, 4 * u)
            p.fr(x + 4.5 * u, y + 9.5 * u, 13 * u * (.3 + .6 * ((t * .5) % 1)), 5 * u)
        }
        "settings" -> {
            save()
            translate(x + 12 * u, y + 12 * u)
            rotate(t * .6)
            repeat(8) {
                rotate(PI / 4)
                p.fr(-1.6 * u, -10 * u, 3.2 * u, 4 * u)
            }
            p.circ(0, 0, 6.5 * u, l)
            p.circ(0, 0, 2.4 * u, l)
            restore()
        }
        "book" -> {
            p.path(pts(12, 6, 3, 4, 3, 19, 12, 21, 21, 19, 21, 4, 12, 6, 12, 21), l)
            if (sin(t * 2) > 0) p.path(pts(12, 6, 17, 3, 17, 17, 12, 21), max(1.5, l * .7))
        }
        "spotify" -> {
            p.circ(x + 12 * u, y + 12 * u, 10 * u, l, true)
            listOf(8.0 to .7, 11.5 to .6, 15.0 to .5).forEachIndexed { i, (yy, w) ->
                p.lw(max(1.5, (2.2 - i * .4) * u))
                strokeStyle = BLACK
                beginPath()
                moveTo(x + (12 - 8 * w) * u, y + yy * u)
                quadraticCurveTo(
                    x + 12 * u,
                    y + (yy - 2.5) * u,
                    x + (12 + 8 * w) * u,
                    y + (yy + .8) * u,
                )
                stroke()
            }
        }
        "call-phone" -> {
            p.rr(x + 6*u, y + 2*u, 12*u, 20*u, 2.5*u, l)
            p.ln(x + 10*u, y + 19*u, x + 14*u, y + 19*u, l)
            val pulse = (1 + sin(t * 6)) * .8 * u
            p.ln(x + 2*u - pulse, y + 7*u, x + 2*u - pulse, y + 16*u, l)
            p.ln(x + 22*u + pulse, y + 7*u, x + 22*u + pulse, y + 16*u, l)
        }
        "youtube-outline" -> {
            p.rr(x + u, y + 5*u, 22*u, 15*u, 4*u, l)
            p.path(pts(10, 9, 16, 12.5, 10, 16, 10, 9), l)
        }
        "youtube" -> {
            p.rr(x + u, y + 5 * u, 22 * u, 15 * u, 4 * u, l, true)
            fillStyle = BLACK
            beginPath()
            moveTo(x + 10 * u, y + 9 * u)
            lineTo(x + 10 * u, y + 16 * u)
            lineTo(x + 16 * u, y + 12.5 * u)
            closePath()
            fill()
        }
        // 24-unit grid, rounded caps/joins, shared l stroke; sparse filled accents.
        // Keep the curl and swept fringe legible at 48 px without portrait texture.
        // 24-unit grid, rounded caps/joins, shared l stroke; matches the placeholder avatar pack.
        "assistant-orb" -> {
            p.circ(x + 12 * u, y + 12 * u, 9 * u, l)
            p.circ(x + 9 * u, y + 10.5 * u, 1.3 * u, 1, true)
            p.circ(x + 15 * u, y + 10.5 * u, 1.3 * u, 1, true)
            p.arc(x + 12 * u, y + 12.5 * u, 3.5 * u, .2 * PI, .8 * PI, max(1.5, l * .7))
        }
        "avatar-portrait" -> {
            save()
            imageSmoothingEnabled = false
            val ok = avatar?.let { runCatching { it.drawPortrait(this, x.toInt(), y.toInt(), s.toInt()) }.getOrDefault(false) } == true
            restore()
            if (!ok) p.circ(x + s / 2, y + s / 2, s / 3, l)
        }
        "sun" -> {
            p.circ(x + 12 * u, y + 12 * u, 5 * u, l, true)
            repeat(8) {
                val a = it * PI / 4 + t * .3
                p.ln(
                    x + 12 * u + cos(a) * 8 * u,
                    y + 12 * u + sin(a) * 8 * u,
                    x + 12 * u + cos(a) * 10.5 * u,
                    y + 12 * u + sin(a) * 10.5 * u,
                    l,
                )
            }
        }
        "cloud" -> {
            p.lw(l)
            beginPath()
            arc(x + 8 * u, y + 14 * u, 4.5 * u, PI * .5, PI * 1.5)
            arc(x + 13 * u, y + 9 * u, 6 * u, PI, 0)
            arc(x + 18 * u, y + 14 * u, 4.5 * u, PI * 1.5, PI * .5)
            closePath()
            stroke()
        }
        "check" -> p.path(pts(4, 12, 10, 18, 20, 6), max(2.0, l * 1.2))
        "cross" -> {
            p.ln(x + 5 * u, y + 5 * u, x + 19 * u, y + 19 * u, l)
            p.ln(x + 19 * u, y + 5 * u, x + 5 * u, y + 19 * u, l)
        }
        "spin" ->
            repeat(8) {
                val a = it * PI / 4
                val on = ((floor(t * 10).toInt() - it) % 8 + 8) % 8 < 3
                p.circ(
                    x + 12 * u + cos(a) * 8 * u,
                    y + 12 * u + sin(a) * 8 * u,
                    (if (on) 2.0 else 1.2) * u,
                    1,
                    on,
                )
            }
        "warn" -> {
            p.poly(pts(12, 2, 23, 21, 1, 21), false, l)
            p.fr(x + 11 * u, y + 8 * u, 2 * u, 7 * u)
            p.fr(x + 11 * u, y + 17 * u, 2 * u, 2 * u)
        }
        "clock" -> {
            p.circ(x + 12 * u, y + 12 * u, 10 * u, l)
            p.ln(
                x + 12 * u,
                y + 12 * u,
                x + 12 * u + cos(t * .8) * 7 * u,
                y + 12 * u + sin(t * .8) * 7 * u,
                l,
            )
            p.ln(x + 12 * u, y + 12 * u, x + 12 * u, y + 6 * u, l)
        }
        "wifi" -> {
            for (r in listOf(9, 6, 3)) p.arc(
                x + 12 * u,
                y + 19 * u,
                r * u * 1.6,
                PI * 1.25,
                PI * 1.75,
                l,
            )
            p.circ(x + 12 * u, y + 19 * u, 1.6 * u, 1, true)
        }
        "cell" ->
            repeat(4) {
                val h = (5 + it * 4) * u
                if (it < 3) p.fr(x + (3 + it * 5) * u, y + 21 * u - h, 3 * u, h)
                else p.rect(x + (3 + it * 5) * u, y + 21 * u - h, 3 * u, h)
            }
        "glasses" -> {
            p.rr(x + u, y + 8 * u, 9 * u, 7 * u, 3 * u, l)
            p.rr(x + 14 * u, y + 8 * u, 9 * u, 7 * u, 3 * u, l)
            p.ln(x + 10 * u, y + 10 * u, x + 14 * u, y + 10 * u, l)
        }
        "buds" -> {
            p.rr(x + 4 * u, y + 4 * u, 6 * u, 10 * u, 3 * u, l)
            p.ln(x + 7 * u, y + 14 * u, x + 7 * u, y + 20 * u, l)
            p.rr(x + 14 * u, y + 4 * u, 6 * u, 10 * u, 3 * u, l)
            p.ln(x + 17 * u, y + 14 * u, x + 17 * u, y + 20 * u, l)
        }
        "ring" -> p.circ(x + 12 * u, y + 12 * u, 8 * u, l * 1.5)
        "phone" -> {
            p.rr(x + 6 * u, y + 2 * u, 12 * u, 20 * u, 2.5 * u, l)
            p.ln(x + 10 * u, y + 19 * u, x + 14 * u, y + 19 * u, l)
        }
        "bright" -> {
            p.circ(x + 12 * u, y + 12 * u, 4 * u, l)
            repeat(8) {
                val a = it * PI / 4
                p.ln(
                    x + 12 * u + cos(a) * 7 * u,
                    y + 12 * u + sin(a) * 7 * u,
                    x + 12 * u + cos(a) * 9.5 * u,
                    y + 12 * u + sin(a) * 9.5 * u,
                    l,
                )
            }
        }
        "plug" -> {
            p.rr(x + 6 * u, y + 8 * u, 12 * u, 9 * u, 2 * u, l)
            p.ln(x + 9 * u, y + 3 * u, x + 9 * u, y + 8 * u, l)
            p.ln(x + 15 * u, y + 3 * u, x + 15 * u, y + 8 * u, l)
            p.ln(x + 12 * u, y + 17 * u, x + 12 * u, y + 22 * u, l)
        }
        "printer" -> {
            p.rect(x + 3 * u, y + 3 * u, 18 * u, 18 * u, l)
            p.ln(x + 3 * u, y + 8 * u, x + 21 * u, y + 8 * u, l)
            p.fr(x + (6 + (t * .6) % 1 * 10) * u, y + 9 * u, 3 * u, 3 * u)
            p.fr(x + 7 * u, y + 18 * u, 10 * u, 2 * u)
        }
        "bulb" -> {
            p.circ(x + 12 * u, y + 10 * u, 6.5 * u, l)
            p.rect(x + 9 * u, y + 16 * u, 6 * u, 5 * u, l)
        }
        "moon" -> {
            p.circ(x + 12 * u, y + 12 * u, 8 * u, l, true)
            fillStyle = BLACK
            beginPath()
            arc(x + 15.5 * u, y + 9 * u, 7 * u, 0, 7)
            fill()
        }
        "alarm" -> {
            p.circ(x + 12 * u, y + 13 * u, 8 * u, l)
            p.ln(x + 12 * u, y + 13 * u, x + 12 * u, y + 8 * u, l)
            p.ln(x + 12 * u, y + 13 * u, x + 16 * u, y + 15 * u, l)
            p.ln(x + 4 * u, y + 4 * u, x + 7 * u, y + 2 * u, l)
            p.ln(x + 20 * u, y + 4 * u, x + 17 * u, y + 2 * u, l)
        }
        "env" -> {
            p.rr(x + 2 * u, y + 3 * u, 20 * u, 13 * u, 2 * u, l)
            p.ln(x + 8 * u, y + 20 * u, x + 16 * u, y + 20 * u, l)
            p.ln(x + 12 * u, y + 16 * u, x + 12 * u, y + 20 * u, l)
        }
        "gauge" -> {
            p.arc(x + 12 * u, y + 15 * u, 9 * u, PI, 0, l)
            val a = PI + PI * (.25 + .5 * abs(sin(t * .7)))
            p.ln(
                x + 12 * u,
                y + 15 * u,
                x + 12 * u + cos(a) * 7 * u,
                y + 15 * u + sin(a) * 7 * u,
                l,
            )
            p.circ(x + 12 * u, y + 15 * u, 1.6 * u, 1, true)
        }
        "plus" -> {
            p.ln(x + 12 * u, y + 5 * u, x + 12 * u, y + 19 * u, l)
            p.ln(x + 5 * u, y + 12 * u, x + 19 * u, y + 12 * u, l)
        }
        "archive" -> {
            p.rect(x + 2 * u, y + 4 * u, 20 * u, 5 * u, l)
            p.rect(x + 4 * u, y + 9 * u, 16 * u, 12 * u, l)
            p.ln(x + 9 * u, y + 13 * u, x + 15 * u, y + 13 * u, l)
        }
        "news" -> {
            p.rect(x + 2 * u, y + 4 * u, 20 * u, 16 * u, l)
            p.fr(x + 5 * u, y + 7 * u, 7 * u, 6 * u)
            listOf(8, 11, 15, 18).forEachIndexed { i, r ->
                p.ln(
                    x + (if (i < 2) 14 else 5) * u,
                    y + r * u,
                    x + 19 * u,
                    y + r * u,
                    max(1.5, l * .7),
                )
            }
        }
        "todo" ->
            listOf(5, 11, 17).forEachIndexed { i, r ->
                p.rect(x + 3 * u, y + (r - 2) * u, 5 * u, 5 * u, max(1.5, l * .8))
                if (i == 0) p.path(pts(3.5, r, 5, r + 1.5, 8, r - 2.5), max(1.5, l * .8))
                p.ln(x + 11 * u, y + (r + .5) * u, x + 21 * u, y + (r + .5) * u, max(1.5, l * .8))
            }
        "party" -> emo("🎉", x, y, s)
        else -> p.rect(x + 2 * u, y + 2 * u, 20 * u, 20 * u, l)
    }
}
