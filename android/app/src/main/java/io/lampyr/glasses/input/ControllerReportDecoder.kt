package io.lampyr.glasses.input

/** Mask/value supports active-low bits, button bitfields, and whole-byte values (e.g. a hat). */
data class ButtonField(val offset: Int, val mask: Int, val value: Int, val letter: String) {
    init {
        require(offset >= 0 && mask in 1..255 && value in 0..255 && value and mask == value)
        require(letter in InputEngine.BIND)
    }
    fun active(report: ByteArray) = report[offset].toInt() and mask == value
}

class ControllerReportDecoder(val reportLength: Int, val fields: List<ButtonField>) {
    data class Changes(val pressed: Set<String>, val released: Set<String>)
    private var down = emptySet<String>()
    init {
        require(reportLength in 1..512 && fields.isNotEmpty())
        require(fields.all { it.offset < reportLength })
        require(fields.map { it.letter }.distinct().size == fields.size)
    }
    /** Wrong-size reports are ignored, never interpreted as a release. */
    fun decode(report: ByteArray): Changes? {
        if (report.size != reportLength) return null
        val next = fields.filter { it.active(report) }.map { it.letter }.toSet()
        return Changes(next - down, down - next).also { down = next }
    }
    fun reset(): Changes = Changes(emptySet(), down).also { down = emptySet() }
}

/** Conservative guided learning: reject counters, multiple independent changes and neutral input. */
object ControllerReportLearning {
    fun derive(neutral: ByteArray, pressed: ByteArray, letter: String): ButtonField? {
        if (neutral.size != pressed.size) return null
        val changed = neutral.indices.filter { neutral[it] != pressed[it] }
        if (changed.size != 1) return null
        val offset = changed.single()
        val delta = (neutral[offset].toInt() xor pressed[offset].toInt()) and 255
        val mask = if (Integer.bitCount(delta) == 1) delta else 255
        return ButtonField(offset, mask, pressed[offset].toInt() and mask, letter)
    }
}
