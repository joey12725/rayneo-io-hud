// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
// Adapted from OpenRayneo ProtocolCodec.kt; see android/NOTICE. Modified for RayNeo iO HUD.
package io.lampyr.glasses.glasses.protocol

import io.lampyr.glasses.debug.Trace

import java.io.ByteArrayOutputStream

data class RayneoFrame(
    val business: Int, val sequence: Int, val payload: ByteArray,
    val flags: Int = 0, val acknowledgement: Boolean = false,
    val address: Int? = null, val slices: ByteArray = byteArrayOf(),
    val receivedAt: Long? = null, // Local metadata, never serialized on the wire.
)

object Crc16Xmodem {
    fun compute(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 255) shl 8)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 0xffff }
        }
        return crc
    }
}

internal fun be16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
internal fun ByteArray.u16(offset: Int) = ((this[offset].toInt() and 255) shl 8) or (this[offset + 1].toInt() and 255)

object FrameCodec {
    fun encode(frame: RayneoFrame): ByteArray {
        require(frame.payload.size <= 65_524)
        require(frame.slices.size <= 7)
        require(frame.business in 0..255 && frame.sequence in 0..65535)
        val flags = (frame.flags and 0x1d) or (if (frame.address != null) 2 else 0) or (frame.slices.size shl 5)
        val body = be16(frame.sequence) + byteArrayOf(flags.toByte()) +
            (frame.address?.let { byteArrayOf(it.toByte()) } ?: byteArrayOf()) +
            frame.slices + byteArrayOf(frame.business.toByte()) + frame.payload
        require(body.size <= 65535)
        return (if (frame.acknowledgement) byteArrayOf(0x55, 0xaa.toByte()) else byteArrayOf(0xaa.toByte(), 0x55)) +
            be16(body.size) + body + be16(Crc16Xmodem.compute(body))
    }

    fun acknowledgement(frame: RayneoFrame) = RayneoFrame(frame.business, frame.sequence, byteArrayOf(), acknowledgement = true)

    /** Transport chunking only. Slice metadata is opaque: never invent a fragment protocol (§3.3). */
    fun segment(bytes: ByteArray, mtu: Int): List<ByteArray> {
        require(mtu > 0)
        return (bytes.indices step mtu).map { bytes.copyOfRange(it, minOf(it + mtu, bytes.size)) }
    }

    class StreamDecoder {
        private var buffered = byteArrayOf()
        private var resyncs = 0L
        private var crcFailures = 0L
        fun append(bytes: ByteArray): List<RayneoFrame> {
            val oldResyncs = resyncs; val oldCrc = crcFailures
            buffered += bytes
            val result = mutableListOf<RayneoFrame>()
            var offset = 0
            while (offset + 4 <= buffered.size) {
                val a = buffered[offset].toInt() and 255
                val b = buffered[offset + 1].toInt() and 255
                if (!((a == 0xaa && b == 0x55) || (a == 0x55 && b == 0xaa))) { resyncs++; offset++; continue }
                val length = buffered.u16(offset + 2)
                if (length < 4) { resyncs++; offset++; continue }
                val end = offset + length + 6
                if (end > buffered.size) break
                val body = buffered.copyOfRange(offset + 4, end - 2)
                if (Crc16Xmodem.compute(body) != buffered.u16(end - 2)) { crcFailures++; resyncs++; offset++; continue }
                val flags = body[2].toInt() and 255
                var cursor = 3
                val address = if (flags and 2 != 0) body.getOrNull(cursor++)?.toInt()?.and(255) else null
                val sliceEnd = cursor + (flags ushr 5)
                if (sliceEnd >= body.size) { resyncs++; offset++; continue }
                val slices = body.copyOfRange(cursor, sliceEnd)
                cursor = sliceEnd
                result += RayneoFrame(body[cursor++].toInt() and 255, body.u16(0), body.copyOfRange(cursor, body.size), flags, a == 0x55, address, slices)
                offset = end
            }
            buffered = buffered.copyOfRange(offset, buffered.size)
            // A valid frame is at most 65541 bytes; preserve only bounded partial input.
            if (buffered.size > 65541) buffered = byteArrayOf()
            if (resyncs != oldResyncs || crcFailures != oldCrc) Trace.log("Parser", "resyncBytes=$resyncs crcFailures=$crcFailures buffered=${buffered.size}")
            return result
        }
    }
}

/** Minimal protobuf wire format, including unknown fixed-width field skipping. No generated/runtime dependency. */
object Proto {
    data class Field(val number: Int, val integer: Long? = null, val bytes: ByteArray? = null)
    class Writer {
        private val out = ByteArrayOutputStream()
        private fun varint(value: Long) {
            var v = value
            while (v and -128L != 0L) { out.write(((v and 127) or 128).toInt()); v = v ushr 7 }
            out.write(v.toInt())
        }
        fun int(number: Int, value: Long) = apply { require(number > 0); varint(number.toLong() shl 3); varint(value) }
        fun bytes(number: Int, value: ByteArray) = apply { require(number > 0); varint((number.toLong() shl 3) or 2); varint(value.size.toLong()); out.write(value) }
        fun text(number: Int, value: String) = bytes(number, value.toByteArray(Charsets.UTF_8))
        fun build(): ByteArray = out.toByteArray()
    }
    fun read(data: ByteArray): List<Field> {
        var cursor = 0
        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                require(cursor < data.size) { "Truncated protobuf varint" }
                val b = data[cursor++].toInt() and 255
                require(shift != 63 || b <= 1) { "Overflowing protobuf varint" }
                value = value or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return value
            }
            error("Invalid varint")
        }
        val result = mutableListOf<Field>()
        while (cursor < data.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            require(number > 0)
            when ((key and 7).toInt()) {
                0 -> result += Field(number, integer = varint())
                2 -> {
                    val length = varint()
                    require(length >= 0 && length <= data.size - cursor)
                    val end = cursor + length.toInt()
                    result += Field(number, bytes = data.copyOfRange(cursor, end)); cursor = end
                }
                1, 5 -> { cursor += if (key and 7 == 1L) 8 else 4; require(cursor <= data.size) }
                else -> error("Unsupported protobuf wire type")
            }
        }
        return result
    }
}

data class Envelope(val type: Int, val json: String = "", val audio: ByteArray? = null, val version: Int = 1) {
    fun encode(): ByteArray = Proto.Writer().int(1, version.toLong()).int(2, type.toLong()).text(3, json).apply {
        audio?.let { bytes(4, it) }
    }.build()
    companion object {
        fun decode(data: ByteArray): Envelope {
            val fields = Proto.read(data).associateBy { it.number }
            return Envelope(fields[2]?.integer?.toInt() ?: 0, fields[3]?.bytes?.toString(Charsets.UTF_8).orEmpty(), fields[4]?.bytes, fields[1]?.integer?.toInt() ?: 1)
        }
    }
}
