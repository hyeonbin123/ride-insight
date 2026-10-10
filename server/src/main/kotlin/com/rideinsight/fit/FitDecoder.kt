package com.rideinsight.fit

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Thrown for any structural problem in a FIT file. Callers should treat the file as unreadable. */
class FitDecodeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Minimal decoder for the Flexible and Interoperable Data Transfer (FIT) binary protocol: file
 * header, definition and data messages (normal and compressed-timestamp headers), developer
 * fields (skipped), CRC-16 check, and the subset of the global profile needed for cycling
 * activities ([FitProfile]). Written from the public protocol description; the decoded values are
 * checked against python-fitparse in the tests.
 */
object FitDecoder {
    /** Seconds between the Unix epoch and the FIT epoch (1989-12-31T00:00:00Z). */
    const val FIT_EPOCH_OFFSET = 631_065_600L

    /** Upper bound on the data size we accept, to keep memory bounded on hostile input. */
    const val MAX_DATA_SIZE = 64 * 1024 * 1024

    private const val MAX_RECORDS = 2_000_000
    private const val MAX_CHAINED_BLOCKS = 16

    /**
     * Decodes a FIT file, including chained files (several header + data + CRC blocks back to
     * back, as some devices write). Trailing bytes that are not a valid chained file are an error.
     */
    fun decode(bytes: ByteArray): FitFile {
        var offset = 0
        var first: FitFile? = null
        val messages = ArrayList<FitMessage>()
        var crcValid = true
        var developerFields = 0
        var blocks = 0
        while (offset < bytes.size) {
            if (++blocks > MAX_CHAINED_BLOCKS) throw FitDecodeException("more than $MAX_CHAINED_BLOCKS chained FIT blocks")
            val part = decodeOne(bytes, offset, MAX_RECORDS - messages.size)
            if (first == null) first = part
            messages.addAll(part.messages)
            crcValid = crcValid && part.crcValid
            developerFields += part.developerFieldDefinitions
            offset += part.header.headerSize + part.header.dataSize + 2
        }
        val header = first ?: throw FitDecodeException("empty file")
        return FitFile(header.header, crcValid, messages, developerFields)
    }

    private fun decodeOne(bytes: ByteArray, offset: Int, messageBudget: Int): FitFile {
        val header = readHeader(bytes, offset)
        val dataEnd = offset + header.headerSize + header.dataSize
        if (dataEnd + 2 > bytes.size) {
            throw FitDecodeException(
                "truncated file: header declares ${header.dataSize} data bytes plus CRC, " +
                    "${bytes.size - offset - header.headerSize} present",
            )
        }
        val crcValid = checkCrc(bytes, offset, dataEnd)

        val buffer = ByteBuffer.wrap(bytes, offset + header.headerSize, header.dataSize).slice()
        val definitions = arrayOfNulls<Definition>(16)
        val messages = ArrayList<FitMessage>()
        var lastTimestamp: Long? = null
        var developerFieldsSeen = 0

        while (buffer.hasRemaining()) {
            val recordHeader = buffer.get().toInt() and 0xFF
            if (recordHeader and 0x80 != 0) {
                // Compressed timestamp header: bits 5-6 local type, bits 0-4 time offset.
                val localType = (recordHeader shr 5) and 0x03
                val offset = (recordHeader and 0x1F).toLong()
                val def = definitions[localType]
                    ?: throw FitDecodeException("data message for undefined local type $localType")
                val base = lastTimestamp
                    ?: throw FitDecodeException("compressed timestamp before any full timestamp")
                var timestamp = (base and 0x1FL.inv()) or offset
                if (offset < (base and 0x1F)) timestamp += 0x20
                lastTimestamp = timestamp
                messages.add(readData(buffer, def, timestamp))
            } else if (recordHeader and 0x40 != 0) {
                val localType = recordHeader and 0x0F
                val hasDeveloperFields = recordHeader and 0x20 != 0
                val def = readDefinition(buffer, hasDeveloperFields)
                if (def.developerFields.isNotEmpty()) developerFieldsSeen++
                definitions[localType] = def
            } else {
                val localType = recordHeader and 0x0F
                val def = definitions[localType]
                    ?: throw FitDecodeException("data message for undefined local type $localType")
                val message = readData(buffer, def, null)
                message.timestampRaw?.let { lastTimestamp = it }
                messages.add(message)
            }
            // The budget is shared by all chained blocks (Codex review 2026-10-10: per-block caps let
            // five small blocks allocate 7.5 million messages from a 7.5 MB file).
            if (messages.size > messageBudget) throw FitDecodeException("more than $MAX_RECORDS messages in file")
        }
        return FitFile(header, crcValid, messages, developerFieldsSeen)
    }

    // ------------------------------------------------------------------ header and crc

    class Header(val headerSize: Int, val protocolVersion: Int, val profileVersion: Int, val dataSize: Int)

    private fun readHeader(bytes: ByteArray, offset: Int): Header {
        val remaining = bytes.size - offset
        if (remaining < 12) throw FitDecodeException("file shorter than a FIT header ($remaining bytes at offset $offset)")
        val headerSize = bytes[offset].toInt() and 0xFF
        if (headerSize != 12 && headerSize != 14) throw FitDecodeException("invalid header size $headerSize at offset $offset")
        if (remaining < headerSize) throw FitDecodeException("file shorter than its header")
        val bb = ByteBuffer.wrap(bytes, offset, headerSize).slice().order(ByteOrder.LITTLE_ENDIAN)
        val protocol = bb.get(1).toInt() and 0xFF
        val profile = bb.getShort(2).toInt() and 0xFFFF
        val dataSize = bb.getInt(4)
        val tag = String(bytes, offset + 8, 4, Charsets.US_ASCII)
        if (tag != ".FIT") throw FitDecodeException("missing .FIT tag in header at offset $offset")
        if (dataSize < 0 || dataSize > MAX_DATA_SIZE) throw FitDecodeException("unreasonable data size $dataSize")
        if (headerSize == 14) {
            val headerCrc = bb.getShort(12).toInt() and 0xFFFF
            if (headerCrc != 0 && headerCrc != crc16(bytes, offset, offset + 12)) {
                throw FitDecodeException("header CRC mismatch at offset $offset")
            }
        }
        return Header(headerSize, protocol, profile, dataSize)
    }

    private fun checkCrc(bytes: ByteArray, start: Int, dataEnd: Int): Boolean {
        val stored = (bytes[dataEnd].toInt() and 0xFF) or ((bytes[dataEnd + 1].toInt() and 0xFF) shl 8)
        return stored == crc16(bytes, start, dataEnd)
    }

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    /** FIT CRC-16 over `bytes[from, to)`. */
    fun crc16(bytes: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            val b = bytes[i].toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[b and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(b shr 4) and 0xF]
        }
        return crc
    }

    // ------------------------------------------------------------------ definitions

    class FieldDef(val number: Int, val size: Int, val baseType: Int)

    class Definition(
        val order: ByteOrder,
        val globalMessage: Int,
        val fields: List<FieldDef>,
        val developerFields: List<FieldDef>,
    ) {
        val dataSize: Int = fields.sumOf { it.size } + developerFields.sumOf { it.size }
    }

    private fun readDefinition(buffer: ByteBuffer, hasDeveloperFields: Boolean): Definition {
        need(buffer, 5, "definition header")
        buffer.get() // reserved
        val architecture = buffer.get().toInt() and 0xFF
        val order = if (architecture == 1) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val globalMessage = readU16(buffer, order)
        val fieldCount = buffer.get().toInt() and 0xFF
        need(buffer, fieldCount * 3, "field definitions")
        val fields = List(fieldCount) {
            FieldDef(buffer.get().toInt() and 0xFF, buffer.get().toInt() and 0xFF, buffer.get().toInt() and 0xFF)
        }
        val developer = if (hasDeveloperFields) {
            need(buffer, 1, "developer field count")
            val count = buffer.get().toInt() and 0xFF
            need(buffer, count * 3, "developer field definitions")
            List(count) {
                // Three bytes each: field number, size, developer data index. Only the size matters
                // here (the bytes are skipped). The first draft read two bytes and desynchronised on
                // every Wahoo ELEMNT file; caught by the python-fitparse comparison test.
                val number = buffer.get().toInt() and 0xFF
                val size = buffer.get().toInt() and 0xFF
                buffer.get() // developer data index
                FieldDef(number, size, FitBaseType.BYTE)
            }
        } else {
            emptyList()
        }
        return Definition(order, globalMessage, fields, developer)
    }

    // ------------------------------------------------------------------ data messages

    private fun readData(buffer: ByteBuffer, def: Definition, compressedTimestamp: Long?): FitMessage {
        need(buffer, def.dataSize, "data message of global type ${def.globalMessage}")
        val values = HashMap<Int, Long>()
        val doubles = HashMap<Int, Double>()
        for (field in def.fields) {
            val start = buffer.position()
            val baseType = field.baseType and 0x1F
            val baseSize = FitBaseType.size(baseType)
            if (baseSize != null && field.size == baseSize && baseType != FitBaseType.STRING) {
                when (baseType) {
                    FitBaseType.FLOAT32 -> {
                        val raw = readU32(buffer, def.order)
                        if (raw != 0xFFFFFFFFL) doubles[field.number] = java.lang.Float.intBitsToFloat(raw.toInt()).toDouble()
                    }
                    FitBaseType.FLOAT64 -> {
                        val raw = buffer.order(def.order).getLong()
                        if (raw != -1L) doubles[field.number] = java.lang.Double.longBitsToDouble(raw)
                    }
                    else -> {
                        val raw = readInteger(buffer, def.order, baseType)
                        if (!FitBaseType.isInvalid(baseType, raw)) values[field.number] = raw
                    }
                }
            }
            buffer.position(start + field.size) // arrays, strings and unknown types are skipped
        }
        for (field in def.developerFields) buffer.position(buffer.position() + field.size)

        val timestampRaw = compressedTimestamp ?: values[FitProfile.FIELD_TIMESTAMP]
        if (compressedTimestamp != null) values[FitProfile.FIELD_TIMESTAMP] = compressedTimestamp
        return FitMessage(def.globalMessage, values, doubles, timestampRaw)
    }

    private fun readInteger(buffer: ByteBuffer, order: ByteOrder, baseType: Int): Long = when (baseType) {
        FitBaseType.ENUM, FitBaseType.UINT8, FitBaseType.UINT8Z, FitBaseType.BYTE -> (buffer.get().toInt() and 0xFF).toLong()
        FitBaseType.SINT8 -> buffer.get().toLong()
        FitBaseType.SINT16 -> buffer.order(order).getShort().toLong()
        FitBaseType.UINT16, FitBaseType.UINT16Z -> readU16(buffer, order).toLong()
        FitBaseType.SINT32 -> buffer.order(order).getInt().toLong()
        FitBaseType.UINT32, FitBaseType.UINT32Z -> readU32(buffer, order)
        FitBaseType.SINT64, FitBaseType.UINT64, FitBaseType.UINT64Z -> buffer.order(order).getLong()
        else -> throw FitDecodeException("unsupported base type $baseType")
    }

    private fun readU16(buffer: ByteBuffer, order: ByteOrder): Int = buffer.order(order).getShort().toInt() and 0xFFFF

    private fun readU32(buffer: ByteBuffer, order: ByteOrder): Long = buffer.order(order).getInt().toLong() and 0xFFFFFFFFL

    private fun need(buffer: ByteBuffer, bytes: Int, what: String) {
        if (buffer.remaining() < bytes) {
            throw FitDecodeException("unexpected end of file while reading $what ($bytes bytes needed, ${buffer.remaining()} left)")
        }
    }
}

/** FIT base type numbers (low five bits of the field definition's base type byte). */
object FitBaseType {
    const val ENUM = 0
    const val SINT8 = 1
    const val UINT8 = 2
    const val SINT16 = 3
    const val UINT16 = 4
    const val SINT32 = 5
    const val UINT32 = 6
    const val STRING = 7
    const val FLOAT32 = 8
    const val FLOAT64 = 9
    const val UINT8Z = 10
    const val UINT16Z = 11
    const val UINT32Z = 12
    const val BYTE = 13
    const val SINT64 = 14
    const val UINT64 = 15
    const val UINT64Z = 16

    fun size(baseType: Int): Int? = when (baseType) {
        ENUM, SINT8, UINT8, STRING, UINT8Z, BYTE -> 1
        SINT16, UINT16, UINT16Z -> 2
        SINT32, UINT32, FLOAT32, UINT32Z -> 4
        FLOAT64, SINT64, UINT64, UINT64Z -> 8
        else -> null
    }

    fun isInvalid(baseType: Int, raw: Long): Boolean = when (baseType) {
        ENUM, UINT8, BYTE -> raw == 0xFFL
        SINT8 -> raw == 0x7FL
        SINT16 -> raw == 0x7FFFL
        UINT16 -> raw == 0xFFFFL
        SINT32 -> raw == 0x7FFFFFFFL
        UINT32 -> raw == 0xFFFFFFFFL
        UINT8Z, UINT16Z, UINT32Z, UINT64Z -> raw == 0L
        SINT64 -> raw == Long.MAX_VALUE
        UINT64 -> raw == -1L
        else -> false
    }
}

/** One decoded data message: raw integer values and float values by field number. */
class FitMessage(
    val globalMessage: Int,
    val values: Map<Int, Long>,
    val doubles: Map<Int, Double>,
    val timestampRaw: Long?,
) {
    fun int(field: Int): Long? = values[field]

    fun double(field: Int): Double? = doubles[field] ?: values[field]?.toDouble()

    /** Field with scale and offset applied: `raw / scale - offset`. */
    fun scaled(field: Int, scale: Double, offset: Double = 0.0): Double? = double(field)?.let { it / scale - offset }

    /** Unix seconds of the FIT `timestamp` field (253). */
    val timestampUnix: Long? get() = values[FitProfile.FIELD_TIMESTAMP]?.let { it + FitDecoder.FIT_EPOCH_OFFSET }
}

class FitFile(
    val header: FitDecoder.Header,
    val crcValid: Boolean,
    val messages: List<FitMessage>,
    val developerFieldDefinitions: Int,
) {
    fun messages(globalMessage: Int): List<FitMessage> = messages.filter { it.globalMessage == globalMessage }
}
