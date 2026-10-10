package com.rideinsight.fit

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tiny FIT encoder for tests: one definition per global message, little-endian, no developer
 * fields. Produces structurally valid files so the tests can tamper with one thing at a time.
 */
class FitWriter {
    private val data = ByteArrayOutputStream()
    private val definitions = HashMap<Int, List<Triple<Int, Int, Int>>>() // local type -> (field, size, base type)

    /** Defines local message [local] for [global] with fields (field number, size, base type). */
    fun define(local: Int, global: Int, fields: List<Triple<Int, Int, Int>>): FitWriter {
        definitions[local] = fields
        data.write(0x40 or local)
        data.write(0) // reserved
        data.write(0) // little endian
        data.write(global and 0xFF)
        data.write(global shr 8)
        data.write(fields.size)
        for ((number, size, base) in fields) {
            data.write(number)
            data.write(size)
            data.write(base)
        }
        return this
    }

    /** Writes a data message; [values] are raw integers in field order. */
    fun data(local: Int, values: List<Long>): FitWriter {
        val fields = definitions.getValue(local)
        require(values.size == fields.size)
        data.write(local)
        for ((i, f) in fields.withIndex()) {
            val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(values[i])
            data.write(bb.array(), 0, f.second)
        }
        return this
    }

    /** Appends raw bytes to the data section (for deliberately broken content). */
    fun raw(bytes: ByteArray): FitWriter {
        data.write(bytes)
        return this
    }

    /** Full file: 14-byte header, data, CRC. [declaredDataSize] overrides the header's size field. */
    fun build(declaredDataSize: Int? = null, breakCrc: Boolean = false): ByteArray {
        val body = data.toByteArray()
        val header = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
        header.put(14).put(0x20).putShort(2170).putInt(declaredDataSize ?: body.size).put(".FIT".toByteArray(Charsets.US_ASCII))
        header.putShort(0) // header CRC optional
        val headerBytes = header.array()
        val all = headerBytes + body
        var crc = FitDecoder.crc16(all, 0, all.size)
        if (breakCrc) crc = crc xor 0x5555
        return all + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    companion object {
        const val FIT_EPOCH = FitDecoder.FIT_EPOCH_OFFSET

        /** A valid cycling ride: file_id + session(sport) + [count] records at 1 s with the given power. */
        fun ride(count: Int, power: Int = 200, startUnix: Long = 1_600_000_000L, backwards: Boolean = false): FitWriter {
            val w = FitWriter()
            w.define(0, FitProfile.MSG_FILE_ID, listOf(Triple(FitProfile.FileId.TYPE, 1, 0x00), Triple(FitProfile.FileId.MANUFACTURER, 2, 0x84)))
            w.data(0, listOf(FitProfile.FileId.TYPE_ACTIVITY, 1L))
            w.define(1, FitProfile.MSG_SESSION, listOf(Triple(FitProfile.Session.SPORT, 1, 0x00)))
            w.data(1, listOf(FitProfile.Sport.CYCLING))
            w.define(2, FitProfile.MSG_RECORD, listOf(Triple(FitProfile.FIELD_TIMESTAMP, 4, 0x86), Triple(FitProfile.Record.POWER, 2, 0x84)))
            val start = startUnix - FIT_EPOCH
            for (i in 0 until count) {
                val t = if (backwards) start + count - i else start + i
                w.data(2, listOf(t, power.toLong()))
            }
            return w
        }
    }
}
