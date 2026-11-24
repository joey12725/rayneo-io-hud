package io.lampyr.glasses

import io.lampyr.glasses.input.*
import io.lampyr.glasses.input.shizuku.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShizukuInputTest {
    private fun record(size: Int, type: Int, code: Int, value: Int, seconds: Long = 1234): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            if (size == 24) { putLong(seconds); putLong(567000) } else { putInt(seconds.toInt()); putInt(567000) }
            putShort(type.toShort()); putShort(code.toShort()); putInt(value)
        }.array()

    @Test fun parsesBothAbiLayoutsSignedValuesAndUnsignedCodes() {
        for (size in listOf(16, 24)) {
            val parser = InputEventParser(size, ByteOrder.LITTLE_ENDIAN)
            assertEquals(listOf(RawInputEvent(1234567, 3, 16, -1), RawInputEvent(1234567, 1, 65535, 2)),
                parser.feed(record(size, 3, 16, -1) + record(size, 1, 65535, 2)))
        }
        assertThrows(IllegalArgumentException::class.java) { InputEventParser(20) }
    }
    @Test fun streamsPartialAndBatchedRecordsWithoutLosingAlignment() {
        for (size in listOf(16, 24)) {
            val bytes = record(size, 1, 23, 1) + record(size, 1, 23, 0)
            for (split in 0..bytes.size) {
                val parser = InputEventParser(size, ByteOrder.LITTLE_ENDIAN)
                assertEquals(listOf(RawInputEvent(1234567, 1, 23, 1), RawInputEvent(1234567, 1, 23, 0)),
                    parser.feed(bytes.copyOfRange(0, split)) + parser.feed(bytes.copyOfRange(split, bytes.size)))
            }
        }
    }
    @Test fun genericKeyboardMappingCoversEveryLetterAndDigit() {
        val linux = listOf(30,48,46,32,18,33,34,35,23,36,37,38,50,49,24,25,16,19,31,20,22,47,17,45,21,44)
        linux.forEachIndexed { index, code -> assertEquals(29 + index, LinuxKeyMap.androidCode(code)) }
        (2..10).forEach { assertEquals(it + 6, LinuxKeyMap.androidCode(it)) }
        assertEquals(7, LinuxKeyMap.androidCode(11))
        mapOf(28 to 66,14 to 67,111 to 112,57 to 62,12 to 69,13 to 70,103 to 19,108 to 20,105 to 21,106 to 22)
            .forEach { (raw, android) -> assertEquals(android, LinuxKeyMap.androidCode(raw)) }
    }
    @Test fun genericGamepadMappingAndRawNamespaceRemainStable() {
        (304..313).forEach { assertEquals(it - 208, LinuxKeyMap.androidCode(it)) }
        mapOf(314 to 109,315 to 108,316 to 110,317 to 106,318 to 107,544 to 19,545 to 20,546 to 21,547 to 22)
            .forEach { (raw, android) -> assertEquals(android, LinuxKeyMap.androidCode(raw)) }
        assertNull(LinuxKeyMap.androidCode(777))
        assertEquals(-778, LinuxKeyMap.bindingCode(777))
        assertEquals("raw 777", LinuxKeyMap.label(-778))
    }
    @Test fun procDevicesMatchesAllExternalControllerNodesAndRejectsPhoneAndNameOnlyMatches() {
        fun block(bus: String, vendor: String, name: String, nodes: String) =
            "I: Bus=$bus Vendor=$vendor Product=9018 Version=0100\nN: Name=\"$name\"\nH: Handlers=kbd $nodes\n"
        val devices = EvdevDevices.parse(block("0005", "2dc8", "8BitDo Micro", "event8 event9") + "\n" +
            block("0005", "2dc8", "8BitDo Micro", "event10") + "\n" +
            block("0019", "2dc8", "8BitDo Micro", "event0") + "\n" + block("0005", "0000", "8BitDo Micro", "event11"))
        val identity = EvdevIdentity("8BitDo Micro", 0x2dc8, 0x9018)
        assertEquals(listOf("/dev/input/event8", "/dev/input/event9", "/dev/input/event10"), devices.filter { it.matches(identity) }.flatMap { it.nodes })
        assertTrue(devices.none { it.matches(identity.copy(name = "other")) })
        assertTrue(devices.none { it.matches(identity.copy(vendor = 0)) })
        assertTrue(devices.none { it.matches(identity.copy(product = 0)) })
        assertTrue(devices.none { it.matches(identity.copy(name = "")) })
    }
    @Test fun hatAxesProduceReleaseBeforeOppositePressAndIndependentDiagonalEdges() {
        val merger = EvdevKeyMerger()
        assertEquals(listOf(KeyTransition(21, 1)), merger.event("a", 3, 16, -1))
        assertEquals(listOf(KeyTransition(19, 1)), merger.event("a", 3, 17, -1))
        assertEquals(emptyList<KeyTransition>(), merger.event("a", 3, 16, -1))
        assertEquals(listOf(KeyTransition(21, 0), KeyTransition(22, 1)), merger.event("a", 3, 16, 1))
        assertEquals(listOf(KeyTransition(19, 0)), merger.event("a", 3, 17, 0))
        assertEquals(listOf(KeyTransition(22, 0)), merger.event("a", 3, 16, 0))
        assertTrue(merger.event("a", 3, 0, 1000).isEmpty())
    }
    @Test fun geteventIoctlFallbackParsesAndroid15OutputWithoutProcOrSysfsAccess() {
        val text = """
            could not get driver version for /dev/input/event99, Permission denied
            add device 1: /dev/input/event8
              bus:      0005
              vendor    2dc8
              product   9018
              version   0100
              name:     "8BitDo Micro"
              location: "bluetooth"
              events:
                KEY (0001): 0017 0018
            add device 2: /dev/input/event9
              bus:      0005
              vendor    2dc8
              product   9018
              name:     "8BitDo Micro"
            add device 3: /dev/input/event0
              bus:      0019
              vendor    0000
              product   0001
              name:     "Power Button"
        """.trimIndent()
        val devices = EvdevDevices.parseGetevent(text)
        assertEquals(3, devices.size)
        assertEquals(listOf("/dev/input/event8", "/dev/input/event9"), devices.filter { it.matches(EvdevIdentity("8BitDo Micro", 0x2dc8, 0x9018)) }.flatMap { it.nodes })
        assertTrue(EvdevDevices.parseGetevent("add device 1: /dev/input/event8\nname: \"Micro\"").isEmpty())
    }
    @Test fun siblingHidApplicationsJoinByPhysicalIdentityWithoutMatchingOtherDevices() {
        val identity = EvdevIdentity("Micro", 0x2dc8, 0x9018)
        val anchor = EvdevDevice("Micro", 5, identity.vendor, identity.product, listOf("event8"), "bluetooth/input0", "AA:BB:CC")
        val consumer = anchor.copy(name = "Micro Consumer Control", nodes = listOf("event9"), physical = "bluetooth/input1")
        val other = consumer.copy(nodes = listOf("event10"), unique = "OTHER", physical = "other/input1")
        val phone = consumer.copy(nodes = listOf("event0"), bus = 25)
        assertEquals(listOf(anchor, consumer), EvdevDevices.matching(listOf(anchor, consumer, other, phone), identity))
        assertEquals(listOf(anchor.copy(unique = ""), consumer.copy(unique = "")),
            EvdevDevices.matching(listOf(anchor.copy(unique = ""), consumer.copy(unique = "")), identity))
        assertTrue(EvdevDevices.matching(listOf(consumer), identity).isEmpty()) // No exact learned anchor.
    }
    @Test fun procDiscoveryRetainsPhysicalAndUniqueIdsForSiblingMatching() {
        val text = "I: Bus=0005 Vendor=2dc8 Product=9018 Version=0100\nN: Name=\"Micro\"\nP: Phys=bt/input0\nU: Uniq=AA:BB\nH: Handlers=kbd event8\n"
        val device = EvdevDevices.parse(text).single()
        assertEquals("bt/input0", device.physical)
        assertEquals("AA:BB", device.unique)
    }
    @Test fun duplicateNodesAndHatPlusDpadKeysMergeUntilLastRelease() {
        val merger = EvdevKeyMerger()
        assertEquals(listOf(KeyTransition(19, 1)), merger.event("a", 1, 103, 1))
        assertTrue(merger.event("b", 1, 544, 1).isEmpty())
        assertTrue(merger.event("b", 3, 17, -1).isEmpty())
        assertTrue(merger.event("a", 1, 103, 0).isEmpty())
        assertTrue(merger.event("b", 1, 544, 0).isEmpty())
        assertEquals(listOf(KeyTransition(19, 0)), merger.remove("b"))
        assertTrue(merger.remove("b").isEmpty())
        assertTrue(merger.event("a", 1, 103, 2).isEmpty()) // Repeat without a down.
    }
    @Test fun dedupeHandlesBothArrivalOrdersAndPreservesTwoFastTaps() {
        for ((first, second) in listOf("raw" to "accessibility", "accessibility" to "raw")) {
            val gate = KeyDeliveryDedupe(100)
            for (time in listOf(1000L, 1040L)) {
                assertTrue(gate.accept(first, 23, 1, time))
                assertFalse(gate.accept(second, 23, 1, time + 20))
                assertTrue(gate.accept(first, 23, 0, time + 10))
                assertFalse(gate.accept(second, 23, 0, time + 30))
            }
            assertTrue(gate.accept(second, 23, 1, 2000))
            assertTrue(gate.accept(first, 23, 1, 2200))
            assertTrue(gate.accept(first, 24, 1, 2200))
        }
    }
    private class FakeFacade : ShizukuInputFacade {
        lateinit var listener: ShizukuInputFacade.Listener
        var closed = false
        override fun start(identity: EvdevIdentity?, listener: ShizukuInputFacade.Listener) { this.listener = listener }
        override fun requestPermission() {}
        override fun refresh() {}
        override fun close() { closed = true }
        fun state(value: ShizukuInputState) = listener.state(value)
        fun key(value: Int, time: Long = 1000, code: Int = 23, node: String = "event8") = listener.event(node, RawInputEvent(time, 1, code, value))
    }
    private class Sink : ControllerSink {
        val edges = mutableListOf<String>()
        var releases = 0
        var confirms = 0
        override fun press(letter: String) { edges += "+$letter" }
        override fun release(letter: String) { edges += "-$letter" }
        override fun releaseAll() { releases++ }
        override fun confirmDown() { confirms++ }
        override fun status(connected: Boolean, detail: String) {}
    }
    private val ready = ShizukuInputState(true, true, true, true, "Micro", listOf("event8"), true)
    @Test fun fakeFacadeDrivesSelectionFallbackAndAutomaticRecoveryWithoutChangingPreference() {
        val sink = Sink(); val selection = ControllerSourceSelection(sink::releaseAll); val facade = FakeFacade()
        val source = ShizukuControllerSource(facade, { null }, { "x" }, sink, selection::shizukuReady)
        selection.select("shizuku"); source.start()
        for (state in listOf(ShizukuInputState(), ready.copy(running = false), ready.copy(granted = false), ready.copy(bound = false), ready.copy(nodes = emptyList()))) {
            facade.state(state); assertTrue(selection.accepts("accessibility")); assertEquals("shizuku", selection.preferred)
        }
        facade.state(ready); assertTrue(selection.accepts("shizuku"))
        facade.key(1); facade.state(ready.copy(running = false, bound = false))
        assertTrue(sink.releases > 0); assertTrue(selection.accepts("accessibility"))
        facade.state(ready); assertTrue(selection.accepts("shizuku"))
        selection.select("gatt"); facade.state(ready.copy(running = false)); assertTrue(selection.accepts("gatt"))
        source.stop(); assertTrue(facade.closed)
    }
    @Test fun sharedDeliveryIsExactlyOnceInEitherOrderAndExclusiveIgnoresAccessibility() {
        for (rawFirst in listOf(true, false)) {
            val facade = FakeFacade(); val sink = Sink()
            val source = ShizukuControllerSource(facade, { null }, { "x" }, sink, {})
            source.start(); facade.state(ready.copy(exclusive = false))
            for (value in listOf(1, 2, 0)) {
                if (rawFirst) { facade.key(value); source.accessibility(37, value, 1020) }
                else { source.accessibility(37, value, 1020); facade.key(value) }
            }
            assertEquals(listOf("+x", "-x"), sink.edges)
            assertEquals(3, sink.confirms)
            facade.state(ready)
            source.accessibility(37, 1, 3000)
            assertEquals(2, sink.edges.size)
        }
    }
    @Test fun lossAndServiceDeathAbandonAllKeysAndNeverManufactureReleaseTaps() {
        val facade = FakeFacade(); val sink = Sink()
        val source = ShizukuControllerSource(facade, { null }, { "l2" }, sink, {})
        source.start(); facade.state(ready); facade.key(1)
        val before = sink.releases
        facade.listener.lost("event8")
        assertEquals(before + 1, sink.releases)
        facade.key(0); assertEquals(listOf("+l2"), sink.edges)
        facade.key(1); facade.state(ready.copy(bound = false)); assertTrue(sink.releases > before + 1)
        source.stop(); facade.key(1); assertEquals(listOf("+l2", "+l2"), sink.edges)
    }
    @Test fun unknownKeysCanBeLearnedDirectlyAndTestModeDoesNotExecuteActions() {
        val facade = FakeFacade(); val sink = Sink(); val learned = mutableListOf<Int>()
        val source = ShizukuControllerSource(facade, { null }, { "x" }, sink, {}, { code, value -> if (value == 1) learned += code; true })
        source.start(); facade.state(ready); facade.key(1, code = 777)
        assertEquals(listOf(-778), learned); assertTrue(sink.edges.isEmpty()); assertTrue(source.lastEvent.contains("raw 777"))
        source.testing = true; facade.key(0, code = 777); assertTrue(sink.edges.isEmpty())
    }
}
