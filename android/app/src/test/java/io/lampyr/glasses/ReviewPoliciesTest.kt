package io.lampyr.glasses

import android.content.pm.ServiceInfo.*
import io.lampyr.glasses.audio.usableDictation
import io.lampyr.glasses.input.GattRetry
import io.lampyr.glasses.service.ForegroundAccess
import org.junit.Assert.*
import org.junit.Test

class ReviewPoliciesTest {
    @Test fun microphoneRequiresPermissionAndEligibilityAndIsRetainedAcrossRecordings() {
        val promotions = mutableListOf<Int>()
        val access = ForegroundAccess({ promotions += it }, { throw AssertionError(it) })
        assertFalse(access.enable(FOREGROUND_SERVICE_TYPE_MICROPHONE, false, true))
        assertFalse(access.enable(FOREGROUND_SERVICE_TYPE_MICROPHONE, true, false))
        assertTrue(promotions.isEmpty())
        assertTrue(access.enable(FOREGROUND_SERVICE_TYPE_MICROPHONE, true, true))
        repeat(3) { assertTrue(access.enable(FOREGROUND_SERVICE_TYPE_MICROPHONE, true, false)) }
        assertEquals(listOf(FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or FOREGROUND_SERVICE_TYPE_MICROPHONE), promotions)
        assertFalse(access.enable(FOREGROUND_SERVICE_TYPE_MICROPHONE, false, false))
    }

    @Test fun rejectedLocationPromotionIsReportedAndCanBeRetriedWithoutLosingMicType() {
        var reject = false; var errors = 0
        val access = ForegroundAccess({ if (reject) throw SecurityException("background") }, { errors++ })
        assertTrue(access.enable(FOREGROUND_SERVICE_TYPE_MICROPHONE, true, true))
        reject = true
        assertFalse(access.enable(FOREGROUND_SERVICE_TYPE_LOCATION, true, true))
        assertEquals(1, errors); assertFalse(access.has(FOREGROUND_SERVICE_TYPE_LOCATION))
        assertTrue(access.has(FOREGROUND_SERVICE_TYPE_MICROPHONE))
        reject = false
        assertTrue(access.enable(FOREGROUND_SERVICE_TYPE_LOCATION, true, true))
        assertTrue(access.has(FOREGROUND_SERVICE_TYPE_MICROPHONE))
    }

    @Test fun bleNeedsUserActionDoesNotRetryAndLinkErrorsBackOffAndResetOnSuccess() {
        val retry = GattRetry()
        assertNull(retry.next(false, "Needs user action: Grant Nearby devices"))
        assertNull(retry.next(false, "Needs user action: Turn on Bluetooth"))
        assertNull(retry.next(false, "Waiting for BLE controller"))
        assertNull(retry.next(false, "Discovering BLE services"))
        assertEquals(listOf(5000L, 10000L, 20000L, 40000L, 80000L, 160000L, 300000L, 300000L),
            List(8) { retry.next(false, "BLE disconnected (133)") })
        assertNull(retry.next(true, "Connected"))
        assertEquals(5000L, retry.next(false, "BLE disconnected (0)"))
    }

    @Test fun emptyAndVeryShortPcmIsDiscardedAtBothCaptureRates() {
        for (rate in listOf(16000, 48000)) {
            assertFalse(usableDictation(0, rate))
            assertFalse(usableDictation(rate * 2 / 5 - 1, rate))
            assertTrue(usableDictation(rate * 2 / 5, rate))
        }
    }
}
