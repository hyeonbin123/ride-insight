package com.rideinsight.fit

// [파일 역할] FIT(Flexible and Interoperable Data Transfer) 바이너리 파일을 바이트 단위로 읽는 디코더다. 파일
// 헤더 → 정의 메시지·데이터 메시지 → CRC 순으로 읽어, 메시지마다 필드 번호별 원시 값을 담은 FitFile을 만든다.
// 필드의 의미(파워, 심박 등)는 FitActivity가 FitProfile 번호표로 붙인다.
// [설계 이유] Garmin FIT SDK 대신 직접 썼다. SDK의 FIT Protocol License가 공개 저장소의 의존성으로 두기에
// 불확실했기 때문이다(docs/plan.md, README). 대신 python-fitparse(MIT)로 샘플 파일을 디코딩한 기대값과 레코드
// 31,156개를 1e-6 안에서 대조하는 테스트(FitDecoderTest)로 정확성을 확인하며, 이 대조가 버그
// 2개(threshold_power 필드 번호, 개발자 필드 정의 크기)를 잡았다(docs/ai-verification.md).
// [보안] 업로드 파일은 신뢰할 수 없는 입력이다. 선언 데이터 크기 상한, 파일 전체가 공유하는 메시지 수 상한,
// 체인 블록 수 상한을 두고, 구조가 어긋나면 어떤 경우든 FitDecodeException 하나만 던져 API가 400으로 답하게
// 한다(5단계 변조 파일 테스트 H8: 500 없음).
// [FIT 구조 요약] 파일 = 헤더(12 또는 14바이트: 데이터 크기와 '.FIT' 표식) + 레코드들 + CRC-16(2바이트).
// 레코드는 1바이트 헤더로 시작하며, 정의 메시지(로컬 타입 0~15에 전역 메시지 번호와 필드 목록을 등록)와
// 데이터 메시지(등록된 정의대로 값만 나열)로 나뉜다. 압축 타임스탬프 헤더는 데이터 메시지 헤더에 시각
// 오프셋을 함께 담아 timestamp 필드를 생략한다.

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FIT 파일의 구조 문제라면 어떤 것이든 이 예외 하나로 던진다. 받는 쪽은 그 파일을 읽을 수 없는 파일로 다뤄야
 * 한다. ApiExceptionHandler가 400 invalid_fit으로 바꾼다.
 */
class FitDecodeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * FIT(Flexible and Interoperable Data Transfer) 바이너리 프로토콜의 최소 디코더: 파일 헤더, 정의·데이터
 * 메시지(일반 헤더와 압축 타임스탬프 헤더), 개발자 필드(건너뜀), CRC-16 검사, 그리고 사이클링 활동에 필요한
 * 전역 프로필의 일부([FitProfile])를 다룬다. 공개된 프로토콜 설명을 보고 작성했고, 디코딩한 값은 테스트에서
 * python-fitparse 결과와 대조한다.
 */
object FitDecoder {
    /**
     * Unix 기준 시각과 FIT 기준 시각(1989-12-31T00:00:00Z) 사이의 초. FIT 시각은 FIT 기준 시각부터의 초라서
     * Unix 초로 바꿀 때 이 값을 더한다(FitActivity, FitMessage.timestampUnix).
     */
    const val FIT_EPOCH_OFFSET = 631_065_600L

    /**
     * 받아들이는 데이터 크기(헤더가 선언하는 값)의 상한(64 MB). 악의적인 입력에도 메모리 사용이 한정되도록,
     * 업로드 한도(10 MB)와 별개로 디코더 자체에도 상한을 둔다.
     */
    const val MAX_DATA_SIZE = 64 * 1024 * 1024

    // 파일 하나(체인 블록 전체 합)에서 읽을 메시지 수 상한.
    private const val MAX_RECORDS = 2_000_000
    // 체인 블록 수 상한. 메시지 상한을 파일 전체로 묶을 때 함께 넣었다(Codex 2차 검토 지적 1: 처음에는 상한이
    // 블록마다 따로 적용돼 7.5 MB 파일로 메시지 750만 개를 만들 수 있었다).
    private const val MAX_CHAINED_BLOCKS = 16

    /**
     * FIT 파일 전체를 읽는 진입점. 일부 기기가 쓰는 체인 파일(헤더 + 데이터 + CRC 블록 여러 개가 연달아 붙은
     * 파일, 예: 활동 파일 뒤에 설정 파일)도 블록을 차례로 읽어 메시지를 합친다. 마지막 블록 뒤에 남은
     * 바이트가 올바른 체인 블록이 아니면 오류다(readHeader가 낸다. fitparse도 이런 파일을 거절한다,
     * docs/ai-verification.md FIT 디코더 설계 판단).
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
            // 남은 메시지 예산(전체 상한 − 지금까지 읽은 수)을 넘겨, 상한이 블록마다가 아니라 파일 전체에
            // 걸리게 한다.
            val part = decodeOne(bytes, offset, MAX_RECORDS - messages.size)
            // 헤더 정보는 첫 블록 것을 대표로 쓰고, CRC는 모든 블록이 맞아야 true다.
            if (first == null) first = part
            messages.addAll(part.messages)
            crcValid = crcValid && part.crcValid
            developerFields += part.developerFieldDefinitions
            // 다음 블록 시작 위치 = 헤더 + 데이터 + CRC 2바이트.
            offset += part.header.headerSize + part.header.dataSize + 2
        }
        // 빈 바이트 배열이면 블록이 하나도 없으므로 거절한다.
        val header = first ?: throw FitDecodeException("empty file")
        return FitFile(header.header, crcValid, messages, developerFields)
    }

    // 블록 하나(헤더 + 데이터 + CRC)를 읽는다. messageBudget은 이 블록에서 더 읽어도 되는 메시지 수다.
    private fun decodeOne(bytes: ByteArray, offset: Int, messageBudget: Int): FitFile {
        val header = readHeader(bytes, offset)
        val dataEnd = offset + header.headerSize + header.dataSize
        // 헤더가 선언한 데이터 크기 + CRC 2바이트가 실제 파일보다 길면 잘린 파일이다(변조 파일 (1) 크기
        // 과대). 데이터를 읽기 전에 거절한다.
        if (dataEnd + 2 > bytes.size) {
            throw FitDecodeException(
                "truncated file: header declares ${header.dataSize} data bytes plus CRC, " +
                    "${bytes.size - offset - header.headerSize} present",
            )
        }
        // CRC가 틀려도 여기서는 거절하지 않고 결과에 표시만 한다. 구조가 멀쩡하면 저장하되 crcValid=false로
        // 알리는 것이 5단계에서 미리 정한 기대 동작이다(변조 파일 (3)).
        val crcValid = checkCrc(bytes, offset, dataEnd)

        // 데이터 구간만 감싼 뷰(slice)로, 복사하지 않는다. 이 버퍼 밖으로는 읽을 수 없으므로 선언 크기가
        // 실제보다 작게 조작된 파일(변조 파일 (2))은 정의·데이터를 읽다가 need()에서 걸린다.
        val buffer = ByteBuffer.wrap(bytes, offset + header.headerSize, header.dataSize).slice()
        // 로컬 메시지 타입(0~15)별로 지금 등록된 정의. 같은 로컬 타입에 새 정의가 오면 덮어쓴다.
        val definitions = arrayOfNulls<Definition>(16)
        val messages = ArrayList<FitMessage>()
        // 마지막으로 본 전체 timestamp(FIT 초). 압축 타임스탬프의 기준이 된다.
        var lastTimestamp: Long? = null
        var developerFieldsSeen = 0

        while (buffer.hasRemaining()) {
            // 레코드 헤더 1바이트로 세 종류를 가른다: 최상위 비트(0x80)가 1이면 압축 타임스탬프 데이터
            // 메시지, 아니고 0x40 비트가 1이면 정의 메시지, 둘 다 0이면 일반 데이터 메시지.
            val recordHeader = buffer.get().toInt() and 0xFF
            if (recordHeader and 0x80 != 0) {
                // 압축 헤더는 로컬 타입이 2비트(0~3)뿐이고, 하위 5비트에 기준 시각으로부터의 오프셋(0~31초)을
                // 담는다(비트 5~6이 로컬 타입, 비트 0~4가 시각 오프셋). 기준이 될 전체 timestamp가 아직
                // 없으면 시각을 복원할 수 없어 오류다.
                val localType = (recordHeader shr 5) and 0x03
                val offset = (recordHeader and 0x1F).toLong()
                val def = definitions[localType]
                    ?: throw FitDecodeException("data message for undefined local type $localType")
                val base = lastTimestamp
                    ?: throw FitDecodeException("compressed timestamp before any full timestamp")
                // 복원 규칙: 기준 시각의 하위 5비트를 오프셋으로 바꾼다. 오프셋이 기준의 하위 5비트보다
                // 작으면 32초 주기를 한 번 넘긴(롤오버) 것이므로 32초를 더한다.
                var timestamp = (base and 0x1FL.inv()) or offset
                if (offset < (base and 0x1F)) timestamp += 0x20
                lastTimestamp = timestamp
                messages.add(readData(buffer, def, timestamp))
            } else if (recordHeader and 0x40 != 0) {
                // 정의 메시지: 하위 4비트가 로컬 타입, 0x20 비트는 개발자 필드 정의가 뒤따른다는 표시. 개발자
                // 필드를 가진 정의 수는 보고용으로 센다.
                val localType = recordHeader and 0x0F
                val hasDeveloperFields = recordHeader and 0x20 != 0
                val def = readDefinition(buffer, hasDeveloperFields)
                if (def.developerFields.isNotEmpty()) developerFieldsSeen++
                definitions[localType] = def
            } else {
                // 일반 데이터 메시지: 등록된 정의가 없으면 데이터 길이를 알 수 없어 거절한다(변조 파일 (4)
                // 정의 없는 데이터 메시지). timestamp 필드가 있는 메시지면 압축 타임스탬프의 기준 시각을
                // 갱신한다.
                val localType = recordHeader and 0x0F
                val def = definitions[localType]
                    ?: throw FitDecodeException("data message for undefined local type $localType")
                val message = readData(buffer, def, null)
                message.timestampRaw?.let { lastTimestamp = it }
                messages.add(message)
            }
            // 메시지 수 상한 검사. 예산은 체인 블록 전체가 공유하므로, 작은 파일로 메시지를 대량으로 만들어
            // 메모리를 쓰게 하는 입력을 막는다(2026-10-10 Codex 2차 검토 지적 1: 블록마다 상한을 따로 두었을
            // 때는 작은 블록 다섯 개로 7.5 MB 파일에서 메시지 750만 개를 만들 수 있었다).
            if (messages.size > messageBudget) throw FitDecodeException("more than $MAX_RECORDS messages in file")
        }
        return FitFile(header, crcValid, messages, developerFieldsSeen)
    }

    // ------------------------------------------------------------------ 헤더와 CRC

    // 블록 헤더: 헤더 길이, 프로토콜·프로필 버전, 데이터 크기(바이트).
    class Header(val headerSize: Int, val protocolVersion: Int, val profileVersion: Int, val dataSize: Int)

    // 헤더를 읽고 검사한다: 남은 길이 12바이트 이상, 헤더 길이 12 또는 14, 오프셋 8~11의 '.FIT' 표식, 데이터
    // 크기 0~64 MB, 14바이트 헤더면 헤더 CRC(값이 0이면 계산하지 않은 것으로 보고 넘어감). 숫자는 리틀
    // 엔디언이다. 텍스트 파일 같은 비 FIT 입력은 여기서 걸린다.
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

    // 블록 끝 2바이트(리틀 엔디언)에 저장된 CRC와, 헤더 시작부터 데이터 끝까지 다시 계산한 CRC를 비교한다.
    private fun checkCrc(bytes: ByteArray, start: Int, dataEnd: Int): Boolean {
        val stored = (bytes[dataEnd].toInt() and 0xFF) or ((bytes[dataEnd + 1].toInt() and 0xFF) shl 8)
        return stored == crc16(bytes, start, dataEnd)
    }

    // FIT CRC-16 계산표(4비트 단위로 조회하는 16개 값).
    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    /**
     * `bytes[from, to)` 구간의 FIT CRC-16. 바이트마다 하위 4비트, 상위 4비트 순서로 표를 조회해 CRC를
     * 갱신한다. 공개된 FIT 프로토콜 설명의 계산법이며, 테스트용 FitWriter도 이 함수로 CRC를 만든다.
     */
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

    // ------------------------------------------------------------------ 정의 메시지

    // 정의 메시지 안의 필드 하나: 필드 번호, 바이트 크기, 기본 타입(base type) 바이트.
    class FieldDef(val number: Int, val size: Int, val baseType: Int)

    // 정의 메시지 하나: 바이트 순서, 전역 메시지 번호, 일반 필드와 개발자 필드 목록. dataSize는 이 정의를
    // 따르는 데이터 메시지 한 개의 바이트 수다.
    class Definition(
        val order: ByteOrder,
        val globalMessage: Int,
        val fields: List<FieldDef>,
        val developerFields: List<FieldDef>,
    ) {
        val dataSize: Int = fields.sumOf { it.size } + developerFields.sumOf { it.size }
    }

    // 정의 메시지 본문: 예약 1바이트, 아키텍처 1바이트(1이면 빅 엔디언), 전역 메시지 번호 2바이트, 필드 수
    // 1바이트, 그 뒤 필드마다 3바이트(번호, 크기, 기본 타입). 단계마다 need()로 남은 바이트를 먼저 확인해,
    // 필드 수 255처럼 조작된 정의가 버퍼를 넘어 읽지 않게 한다(변조 파일 (5)).
    private fun readDefinition(buffer: ByteBuffer, hasDeveloperFields: Boolean): Definition {
        need(buffer, 5, "definition header")
        buffer.get() // 예약 바이트
        val architecture = buffer.get().toInt() and 0xFF
        val order = if (architecture == 1) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val globalMessage = readU16(buffer, order)
        val fieldCount = buffer.get().toInt() and 0xFF
        need(buffer, fieldCount * 3, "field definitions")
        val fields = List(fieldCount) {
            FieldDef(buffer.get().toInt() and 0xFF, buffer.get().toInt() and 0xFF, buffer.get().toInt() and 0xFF)
        }
        // 개발자 필드(앱이 정의한 사용자 필드)는 값을 쓰지 않으므로 크기만 알아 두고 데이터에서 건너뛴다.
        val developer = if (hasDeveloperFields) {
            need(buffer, 1, "developer field count")
            val count = buffer.get().toInt() and 0xFF
            need(buffer, count * 3, "developer field definitions")
            List(count) {
                // 개발자 필드 정의는 하나에 3바이트다: 필드 번호, 크기, 개발자 데이터 인덱스. 여기서는
                // 크기만 필요하다(값 바이트는 건너뛴다). 처음 구현은 2바이트만 읽어 모든 Wahoo ELEMNT
                // 파일에서 읽기 위치가 어긋났고, python-fitparse 대조 테스트가 이를 잡았다.
                val number = buffer.get().toInt() and 0xFF
                val size = buffer.get().toInt() and 0xFF
                buffer.get() // 개발자 데이터 인덱스
                FieldDef(number, size, FitBaseType.BYTE)
            }
        } else {
            emptyList()
        }
        return Definition(order, globalMessage, fields, developer)
    }

    // ------------------------------------------------------------------ 데이터 메시지

    // 데이터 메시지 하나를 정의대로 읽는다. 정수는 values, 실수는 doubles에 필드 번호로 담고, 기본 타입이
    // 정한 무효 값(예: uint16의 0xFFFF)은 값이 없는 것으로 보고 넣지 않는다. compressedTimestamp가 있으면
    // 압축 헤더에서 복원한 시각을 timestamp 필드에 넣는다.
    private fun readData(buffer: ByteBuffer, def: Definition, compressedTimestamp: Long?): FitMessage {
        need(buffer, def.dataSize, "data message of global type ${def.globalMessage}")
        val values = HashMap<Int, Long>()
        val doubles = HashMap<Int, Double>()
        // 기본 타입 바이트의 하위 5비트가 타입 번호다. 필드 크기가 그 타입 크기와 같은 단일 값만 해석하고,
        // 배열(크기가 배수), 문자열, 모르는 타입은 해석하지 않는다. 실수 타입은 모든 비트가 1이면 무효
        // 값이다.
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
            // 해석했든 안 했든 정의된 크기만큼 정확히 앞으로 가야 다음 필드 위치가 어긋나지 않는다.
            buffer.position(start + field.size) // 배열·문자열·모르는 타입은 여기서 건너뛴다
        }
        // 개발자 필드 바이트는 건너뛴다.
        for (field in def.developerFields) buffer.position(buffer.position() + field.size)

        val timestampRaw = compressedTimestamp ?: values[FitProfile.FIELD_TIMESTAMP]
        if (compressedTimestamp != null) values[FitProfile.FIELD_TIMESTAMP] = compressedTimestamp
        return FitMessage(def.globalMessage, values, doubles, timestampRaw)
    }

    // 기본 타입별로 바이트 수와 부호를 맞춰 Long으로 읽는다. 부호 없는 값은 비트 마스크(and 0xFF 등)로 음수가
    // 되지 않게 한다. 위 분기에서 걸러지지 않은 타입은 FitDecodeException.
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

    // 부호 없는 16·32비트 정수 읽기(정의의 바이트 순서를 적용).
    private fun readU16(buffer: ByteBuffer, order: ByteOrder): Int = buffer.order(order).getShort().toInt() and 0xFFFF

    private fun readU32(buffer: ByteBuffer, order: ByteOrder): Long = buffer.order(order).getInt().toLong() and 0xFFFFFFFFL

    // 남은 바이트가 모자라면 FitDecodeException. ByteBuffer의 BufferUnderflowException 같은 다른 예외가 새어
    // 나가 500이 되지 않도록 읽기 전에 항상 부른다.
    private fun need(buffer: ByteBuffer, bytes: Int, what: String) {
        if (buffer.remaining() < bytes) {
            throw FitDecodeException("unexpected end of file while reading $what ($bytes bytes needed, ${buffer.remaining()} left)")
        }
    }
}

/**
 * FIT 기본 타입 번호(필드 정의의 기본 타입 바이트 중 하위 5비트). 그 바이트의 최상위 비트(0x80)는 다중 바이트
 * 값의 엔디언 표시라 readData에서 하위 5비트만 본다.
 */
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

    // 기본 타입 하나의 바이트 크기. 모르는 타입은 null(해석하지 않고 건너뜀).
    fun size(baseType: Int): Int? = when (baseType) {
        ENUM, SINT8, UINT8, STRING, UINT8Z, BYTE -> 1
        SINT16, UINT16, UINT16Z -> 2
        SINT32, UINT32, FLOAT32, UINT32Z -> 4
        FLOAT64, SINT64, UINT64, UINT64Z -> 8
        else -> null
    }

    // FIT의 값 없음 표시: 부호 없는 정수는 최댓값(모든 비트 1), 부호 있는 정수는 양의 최댓값, z가 붙은
    // 타입(uint8z 등)은 0이다. 센서가 없는 필드는 이 값으로 기록될 수 있어 null로 다룬다.
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

/**
 * 디코딩한 데이터 메시지 하나: 필드 번호별 원시 정수 값과 실수 값. int(n)은 원시 정수, double(n)은 실수
 * 필드가 있으면 그 값을, 없으면 정수를 실수로 바꿔 준다. 값이 없으면 둘 다 null.
 */
class FitMessage(
    val globalMessage: Int,
    val values: Map<Int, Long>,
    val doubles: Map<Int, Double>,
    val timestampRaw: Long?,
) {
    fun int(field: Int): Long? = values[field]

    fun double(field: Int): Double? = doubles[field] ?: values[field]?.toDouble()

    /** FIT 프로필의 배율·오프셋을 적용한 필드 값: `raw / scale - offset`(예: 고도 raw / 5 − 500 m). */
    fun scaled(field: Int, scale: Double, offset: Double = 0.0): Double? = double(field)?.let { it / scale - offset }

    /** FIT `timestamp` 필드(253)를 Unix 초로 바꾼 값. */
    val timestampUnix: Long? get() = values[FitProfile.FIELD_TIMESTAMP]?.let { it + FitDecoder.FIT_EPOCH_OFFSET }
}

// 디코딩 결과: 첫 블록 헤더, CRC 일치 여부(모든 블록), 메시지 목록(파일 순서), 개발자 필드 정의 수.
// messages(n)은 전역 메시지 번호로 걸러 준다.
class FitFile(
    val header: FitDecoder.Header,
    val crcValid: Boolean,
    val messages: List<FitMessage>,
    val developerFieldDefinitions: Int,
) {
    fun messages(globalMessage: Int): List<FitMessage> = messages.filter { it.globalMessage == globalMessage }
}
