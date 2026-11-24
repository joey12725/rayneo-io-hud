package io.lampyr.glasses

import io.lampyr.glasses.input.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ControllerReportDecoderTest {
    private fun decoder() = ControllerReportDecoder(3, listOf(
        ButtonField(0, 1, 1, "y"), ButtonField(0, 128, 0, "l2"), ButtonField(2, 255, 4, "up")))
    @Test fun diffsReportsAndHandlesActiveLowAndByteValues() {
        val decoder = decoder()
        assertEquals(ControllerReportDecoder.Changes(emptySet(), emptySet()), decoder.decode(byteArrayOf(128.toByte(), 0, 8)))
        assertEquals(setOf("y", "l2", "up"), decoder.decode(byteArrayOf(1, 77, 4))!!.pressed)
        assertEquals(ControllerReportDecoder.Changes(emptySet(), emptySet()), decoder.decode(byteArrayOf(1, 76, 4)))
        assertEquals(setOf("y", "l2", "up"), decoder.decode(byteArrayOf(128.toByte(), 1, 8))!!.released)
    }
    @Test fun malformedReportDoesNotLoseHeldStateAndResetReleasesEverything() {
        val decoder = decoder()
        decoder.decode(byteArrayOf(1, 0, 4))
        assertNull(decoder.decode(byteArrayOf(0)))
        assertEquals(setOf("y", "l2", "up"), decoder.reset().released)
        assertTrue(decoder.reset().released.isEmpty())
        assertEquals(setOf("y", "l2", "up"), decoder.decode(byteArrayOf(1, 0, 4))!!.pressed)
    }
    @Test fun independentButtonsCanBeHeldTogether() {
        val decoder = ControllerReportDecoder(1, listOf(ButtonField(0, 1, 1, "r2"), ButtonField(0, 2, 2, "up")))
        assertEquals(setOf("r2"), decoder.decode(byteArrayOf(1))!!.pressed)
        assertEquals(setOf("up"), decoder.decode(byteArrayOf(3))!!.pressed)
        assertEquals(setOf("r2"), decoder.decode(byteArrayOf(2))!!.released)
    }
    @Test fun invalidProfilesFailClosed() {
        listOf<() -> Unit>(
            { ButtonField(-1, 1, 1, "y") }, { ButtonField(0, 0, 0, "y") },
            { ButtonField(0, 1, 2, "y") }, { ButtonField(0, 1, 1, "invalid") },
            { ControllerReportDecoder(0, emptyList()) },
            { ControllerReportDecoder(1, listOf(ButtonField(1, 1, 1, "y"))) },
            { ControllerReportDecoder(1, listOf(ButtonField(0, 1, 1, "y"), ButtonField(0, 2, 2, "y"))) },
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
    }
    @Test fun learningHandlesBitsAndRejectsAmbiguousReports() {
        assertEquals(ButtonField(0, 4, 4, "x"), ControllerReportLearning.derive(byteArrayOf(0), byteArrayOf(4), "x"))
        assertEquals(ButtonField(0, 128, 0, "l2"), ControllerReportLearning.derive(byteArrayOf(128.toByte()), byteArrayOf(0), "l2"))
        assertEquals(ButtonField(0, 255, 4, "up"), ControllerReportLearning.derive(byteArrayOf(8), byteArrayOf(4), "up"))
        assertNull(ControllerReportLearning.derive(byteArrayOf(0), byteArrayOf(0), "y"))
        assertNull(ControllerReportLearning.derive(byteArrayOf(0), byteArrayOf(1, 0), "y"))
        assertNull(ControllerReportLearning.derive(byteArrayOf(0, 0), byteArrayOf(1, 1), "y"))
    }
    @Test fun hidAndProtectedReportUuidsAreAlwaysExcluded() {
        fun uuid(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
        val vendor = UUID.fromString("12345678-0000-1000-8000-00805f9b34fb")
        assertFalse(NonHidGattSession.allowed(uuid("1812"), vendor))
        for (short in listOf("2a4a", "2a4b", "2a4c", "2a4d")) assertFalse(NonHidGattSession.allowed(vendor, uuid(short)))
        assertTrue(NonHidGattSession.allowed(vendor, vendor))
    }
    @Test fun selectionReleasesBeforeSwitchingAndNeverAcceptsBothSources() {
        var releases = 0
        val selection = ControllerSourceSelection { releases++ }
        assertTrue(selection.accepts("accessibility"))
        assertFalse(selection.accepts("gatt"))
        selection.select("gatt")
        assertEquals(1, releases)
        assertTrue(selection.accepts("gatt")); assertFalse(selection.accepts("accessibility"))
        selection.select("gatt"); assertEquals(1, releases)
        selection.select("accessibility"); assertEquals(2, releases)
        assertThrows(IllegalArgumentException::class.java) { selection.select("unknown") }
    }
    @Test fun consumptionUsesLearnedIdentityRegardlessOfMappingOrServiceAvailability() {
        val pad = ControllerIdentity("pad-descriptor", 123, 456)
        assertTrue(ControllerKeyPolicy.consume(pad, pad.copy()))
        assertFalse(ControllerKeyPolicy.consume(null, pad))
        assertFalse(ControllerKeyPolicy.consume(pad, null))
        assertFalse(ControllerKeyPolicy.consume(pad, pad.copy(descriptor = "other-keyboard")))
        assertFalse(ControllerKeyPolicy.consume(pad, pad.copy(vendor = 9)))
        assertFalse(ControllerKeyPolicy.consume(pad, pad.copy(product = 9)))
        assertFalse(ControllerKeyPolicy.consume(ControllerIdentity("", 0, 0), ControllerIdentity("", 0, 0)))
    }
}
