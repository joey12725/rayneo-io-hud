// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
// Adapted from OpenRayneo guest authentication; see android/NOTICE. Modified for RayNeo iO HUD.
package io.lampyr.glasses.glasses.protocol

import java.security.MessageDigest

object Pairing {
    private val constant = byteArrayOf(7, 18, 0xc8.toByte(), 0xad.toByte(), 39, 116, 65, 0x95.toByte(), 0xde.toByte(), 95, 0xa1.toByte(), 0xea.toByte(), 109, 2, 95, 0xb8.toByte())
    fun proof(random: ByteArray, phone: ByteArray, glasses: ByteArray): ByteArray {
        require(random.size == 4 && phone.size == 6 && glasses.size == 6)
        return MessageDigest.getInstance("SHA-256").digest(random + phone + glasses + constant)
    }
    fun encode(command: Int, fields: List<Pair<Int, ByteArray>>): ByteArray {
        val inner = fields.fold(byteArrayOf()) { acc, (tag, value) -> acc + byteArrayOf(tag.toByte()) + be16(value.size) + value }
        require(inner.size <= 65521)
        return byteArrayOf(command.toByte()) + be16(inner.size) + inner
    }
    fun fields(payload: ByteArray): Map<Int, ByteArray> {
        require(payload.size >= 3 && payload.u16(1) == payload.size - 3)
        val result = linkedMapOf<Int, ByteArray>()
        var offset = 3
        while (offset < payload.size) {
            require(offset + 3 <= payload.size)
            val length = payload.u16(offset + 1)
            val end = offset + 3 + length
            require(end <= payload.size)
            result[payload[offset].toInt() and 255] = payload.copyOfRange(offset + 3, end)
            offset = end
        }
        return result
    }
    fun phoneId(androidId: String): ByteArray {
        require(androidId.length >= 12 && androidId.all { it.digitToIntOrNull(16) != null }) { "ANDROID_ID unavailable" }
        return androidId.takeLast(12).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    fun address(bytes: ByteArray) = bytes.joinToString(":") { "%02X".format(it.toInt() and 255) }
}

/** RUCH BleIdentityGate: consume only the first MTU callback after discovery. */
internal class BleIdentityGate {
    private var requested = false
    private var consumed = false
    @Synchronized fun servicesReady(): Boolean = if (requested) false else { requested = true; true }
    @Synchronized fun mtuReady(): Boolean = if (!requested || consumed) false else { consumed = true; true }
}
