package io.lampyr.glasses.render

import kotlin.math.*

val EMO_KEYS =
    listOf(
        "😂",
        "👀",
        "😭",
        "❤️",
        "❗",
        "☝️",
        "😅",
        "🥰",
        "😜",
        "😁",
        "🖕",
        "💀",
        "✅",
        "❌",
        "👍",
        "👎",
        "🔥",
        "🙏",
        "😮",
        "😢",
        "🎉",
        "💯",
        "🤔",
        "😍",
        "🙄",
    )

fun emoKey(ch: String): String? =
    (EMO_KEYS + listOf("\uE001", "\uE002")).find { it.replace("️", "") == ch.replace("️", "") }

private data class Face(
    val e: String = "dot",
    val m: String = "smile",
    val e1: String? = null,
    val e2: String? = null,
    val extras: List<String> = emptyList(),
)

private val faces =
    mapOf(
        "😂" to Face(m = "laugh", e1 = "squint", e2 = "squintR", extras = listOf("tears")),
        "😭" to Face("happy", "wail", extras = listOf("streams")),
        "😅" to Face("happy", "grin", extras = listOf("drop")),
        "🥰" to Face("happy", "smile", extras = listOf("hearts")),
        "😜" to Face(m = "tongue", e1 = "wink", e2 = "dot"),
        "😁" to Face("happy", "grin"),
        "😮" to Face("dot", "open"),
        "😢" to Face("dot", "frown", extras = listOf("tear1")),
        "😍" to Face("heart", "smile"),
        "🙄" to Face("roll", "flat"),
        "🤔" to Face("dot", "hmm", extras = listOf("hand", "brow")),
    )

private fun Gfx.face(x: Double, y: Double, s: Double, f: Face) {
    val c = s / 2
    val l = max(2.0, s / 12)
    p.circ(x + c, y + c, c - l, l)
    val ex = s * .32
    val ey = s * .4
    val er = max(1.5, s * .06)
    val my = s * .62
    fun eye(k: String, px: Double) {
        val xx = x + px
        val yy = y + ey
        when (k) {
            "dot" -> p.circ(xx, yy, er, 1, true)
            "happy" ->
                p.path(
                    points(
                        xx - s * .08,
                        yy + s * .03,
                        xx,
                        yy - s * .05,
                        xx + s * .08,
                        yy + s * .03,
                    ),
                    l,
                )
            "squint" ->
                p.path(
                    points(
                        xx - s * .08,
                        yy - s * .05,
                        xx + s * .06,
                        yy,
                        xx - s * .08,
                        yy + s * .05,
                    ),
                    l,
                )
            "squintR" ->
                p.path(
                    points(
                        xx + s * .08,
                        yy - s * .05,
                        xx - s * .06,
                        yy,
                        xx + s * .08,
                        yy + s * .05,
                    ),
                    l,
                )
            "heart" -> p.heart(xx, yy, s * .24, true)
            "wink" -> p.ln(xx - s * .08, yy, xx + s * .08, yy, l)
            "roll" -> {
                p.circ(xx, yy, s * .09, 1.5)
                p.circ(xx, yy - s * .05, er * .8, 1, true)
            }
            "wide" -> {
                p.circ(xx, yy, s * .08, 1.5)
                p.circ(xx, yy, er * .7, 1, true)
            }
            "x" -> {
                p.ln(xx - s * .06, yy - s * .06, xx + s * .06, yy + s * .06, l)
                p.ln(xx + s * .06, yy - s * .06, xx - s * .06, yy + s * .06, l)
            }
        }
    }
    eye(f.e1 ?: f.e, c - ex * .55)
    eye(f.e2 ?: f.e, c + ex * .55)
    val cx = x + c
    val yy = y + my
    when (f.m) {
        "smile" -> p.arc(cx, yy - s * .08, s * .2, .2 * PI, .8 * PI, l)
        "grin" -> {
            p.rr(cx - s * .2, yy - s * .04, s * .4, s * .16, s * .05, l)
            p.ln(cx - s * .18, yy + s * .04, cx + s * .18, yy + s * .04, 1)
        }
        "laugh" ->
            p.poly(
                points(
                    cx - s * .22,
                    yy - s * .04,
                    cx + s * .22,
                    yy - s * .04,
                    cx + s * .12,
                    yy + s * .14,
                    cx - s * .12,
                    yy + s * .14,
                )
            )
        "open" -> p.circ(cx, yy + s * .03, s * .08, l)
        "frown" -> p.arc(cx, yy + s * .16, s * .17, 1.2 * PI, 1.8 * PI, l)
        "flat" -> p.ln(cx - s * .14, yy + s * .04, cx + s * .14, yy + s * .04, l)
        "tongue" -> {
            p.arc(cx, yy - s * .08, s * .2, .2 * PI, .8 * PI, l)
            p.rr(cx + s * .02, yy + s * .06, s * .12, s * .14, s * .06, l, true)
        }
        "wail" -> p.rr(cx - s * .16, yy - s * .06, s * .32, s * .2, s * .08, l, true)
        "hmm" -> p.path(points(cx - s * .12, yy + s * .06, cx + s * .12, yy), l)
    }
    f.extras.forEach {
        when (it) {
            "tears" -> {
                p.ln(x + s * .14, y + s * .5, x + s * .08, y + s * .72, l)
                p.ln(x + s * .86, y + s * .5, x + s * .92, y + s * .72, l)
            }
            "streams" -> {
                p.fr(x + s * .27, y + s * .47, l, s * .4)
                p.fr(x + s * .7, y + s * .47, l, s * .4)
            }
            "drop" -> {
                p.poly(
                    points(
                        x + s * .86,
                        y + s * .1,
                        x + s * .94,
                        y + s * .3,
                        x + s * .78,
                        y + s * .3,
                    )
                )
                p.circ(x + s * .86, y + s * .3, s * .07, 1, true)
            }
            "tear1" -> p.circ(x + s * .7, y + s * .6, s * .05, 1, true)
            "hearts" -> {
                p.heart(x + s * .12, y + s * .12, s * .22)
                p.heart(x + s * .9, y + s * .2, s * .18)
                p.heart(x + s * .12, y + s * .86, s * .16)
            }
            "hand" -> p.rr(x + s * .18, y + s * .72, s * .34, s * .2, s * .06, l, true)
            "brow" -> p.ln(x + s * .6, y + s * .25, x + s * .78, y + s * .2, l)
        }
    }
}

fun Gfx.emo(key: String, xn: Number, yn: Number, sn: Number, tone: Int = this.tone) = withTone(tone) { emoShape(key, xn, yn, sn) }

private fun Gfx.emoShape(key: String, xn: Number, yn: Number, sn: Number) {
    val x = xn.toDouble()
    val y = yn.toDouble()
    val s = sn.toDouble()
    val k = emoKey(key) ?: key
    faces[k]?.let {
        face(x, y, s, it)
        return
    }
    when (k) {
        "👀" ->
            listOf(.3, .7).forEach {
                save()
                translate(x + s * it, y + s * .5)
                scale(.75, 1)
                p.circ(0, 0, s * .24, max(2.0, s / 14))
                restore()
                p.circ(x + s * it + s * .06, y + s * .55, s * .09, 1, true)
            }
        "❤️" -> p.heart(x + s / 2, y + s * .55, s * .95, true)
        "❗" -> {
            p.poly(
                points(
                    x + s * .38,
                    y + s * .08,
                    x + s * .62,
                    y + s * .08,
                    x + s * .56,
                    y + s * .66,
                    x + s * .44,
                    y + s * .66,
                )
            )
            p.circ(x + s / 2, y + s * .84, s * .09, 1, true)
        }
        "☝️" -> {
            p.rr(x + s * .42, y + s * .04, s * .16, s * .5, s * .08, 2, true)
            p.rr(x + s * .26, y + s * .44, s * .48, s * .5, s * .1, 2, true)
            p.ln(x + s * .32, y + s * .6, x + s * .66, y + s * .6, 1)
        }
        "🖕" -> {
            p.rr(x + s * .43, y + s * .04, s * .15, s * .48, s * .07, 2, true)
            p.rr(x + s * .24, y + s * .4, s * .52, s * .54, s * .1, 2, true)
            p.fr(x + s * .36, y + s * .4, 2, s * .2, BLACK)
            p.fr(x + s * .62, y + s * .4, 2, s * .2, BLACK)
        }
        "💀" -> {
            p.circ(x + s / 2, y + s * .42, s * .36, 1, true)
            p.fr(x + s * .3, y + s * .6, s * .4, s * .3)
            fillStyle = BLACK
            for (a in listOf(.36, .64)) {
                beginPath()
                arc(x + s * a, y + s * .42, s * .1, 0, 7)
                fill()
            }
            p.fr(x + s * .46, y + s * .56, s * .08, s * .08, BLACK)
            for (a in listOf(.38, .5, .62)) p.fr(x + s * a - 1, y + s * .74, 2, s * .16, BLACK)
        }
        "✅" -> {
            p.rr(x + s * .06, y + s * .06, s * .88, s * .88, s * .16, 2, true)
            p.lw(max(3.0, s / 8))
            strokeStyle = BLACK
            beginPath()
            moveTo(x + s * .26, y + s * .52)
            lineTo(x + s * .43, y + s * .7)
            lineTo(x + s * .76, y + s * .3)
            stroke()
        }
        "❌" -> {
            val l = max(3.0, s / 6)
            p.ln(x + s * .18, y + s * .18, x + s * .82, y + s * .82, l)
            p.ln(x + s * .82, y + s * .18, x + s * .18, y + s * .82, l)
        }
        "👍" -> {
            p.rr(x + s * .08, y + s * .46, s * .18, s * .46, s * .04, 2, true)
            p.rr(x + s * .32, y + s * .42, s * .56, s * .5, s * .1, 2, true)
            p.poly(
                points(
                    x + s * .36,
                    y + s * .46,
                    x + s * .5,
                    y + s * .06,
                    x + s * .62,
                    y + s * .1,
                    x + s * .6,
                    y + s * .46,
                )
            )
        }
        "👎" -> {
            save()
            translate(0, y * 2 + s)
            scale(1, -1)
            emo("👍", x, y, s)
            restore()
        }
        "🔥" -> {
            beginPath()
            moveTo(x + s * .5, y + s * .04)
            bezierCurveTo(x + s * .95, y + s * .4, x + s * .9, y + s * .95, x + s * .5, y + s * .96)
            bezierCurveTo(x + s * .08, y + s * .95, x + s * .05, y + s * .5, x + s * .3, y + s * .3)
            bezierCurveTo(x + s * .35, y + s * .5, x + s * .45, y + s * .5, x + s * .5, y + s * .04)
            fillStyle = GREEN
            fill()
            beginPath()
            moveTo(x + s * .5, y + s * .5)
            bezierCurveTo(x + s * .7, y + s * .7, x + s * .62, y + s * .9, x + s * .5, y + s * .9)
            bezierCurveTo(x + s * .38, y + s * .9, x + s * .32, y + s * .72, x + s * .5, y + s * .5)
            fillStyle = BLACK
            fill()
        }
        "🙏" -> {
            p.poly(
                points(
                    x + s * .48,
                    y + s * .06,
                    x + s * .48,
                    y + s * .9,
                    x + s * .2,
                    y + s * .9,
                    x + s * .36,
                    y + s * .3,
                )
            )
            p.poly(
                points(
                    x + s * .52,
                    y + s * .06,
                    x + s * .52,
                    y + s * .9,
                    x + s * .8,
                    y + s * .9,
                    x + s * .64,
                    y + s * .3,
                )
            )
        }
        "🎉" -> {
            p.poly(points(x + s * .08, y + s * .92, x + s * .3, y + s * .3, x + s * .7, y + s * .7))
            listOf(.6 to .12, .84 to .3, .72 to .46, .92 to .6, .5 to .22).forEach { (a, b) ->
                p.fr(x + s * a, y + s * b, max(2.0, s * .07), max(2.0, s * .07))
            }
            p.arc(x + s * .62, y + s * .5, s * .3, -1.4, -.6, 2)
        }
        "💯" -> {
            val l = max(2.0, s / 12)
            p.ln(x + s * .12, y + s * .22, x + s * .12, y + s * .66, l)
            p.circ(x + s * .4, y + s * .44, s * .15, l)
            p.circ(x + s * .75, y + s * .44, s * .15, l)
            p.ln(x + s * .1, y + s * .8, x + s * .9, y + s * .8, l)
            p.ln(x + s * .14, y + s * .92, x + s * .86, y + s * .92, l)
        }
        "\uE001" -> {
            val l = max(2.0, s / 11)
            save()
            translate(x + s / 2, y + s / 2)
            rotate(-PI / 4)
            p.rr(-s * .44, -s * .14, s * .48, s * .28, s * .14, l)
            p.rr(-s * .04, -s * .14, s * .48, s * .28, s * .14, l)
            restore()
        }
        "\uE002" -> {
            p.rr(x + s * .06, y + s * .12, s * .88, s * .76, s * .18, max(2.0, s / 11))
            p.poly(
                points(x + s * .4, y + s * .32, x + s * .4, y + s * .68, x + s * .68, y + s * .5)
            )
        }
        else -> p.rr(x + 2, y + 2, s - 4, s - 4, 3, 2)
    }
}
