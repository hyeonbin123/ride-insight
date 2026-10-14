package com.rideinsight.fit

// [파일 역할] 테스트 전용의 아주 작은 FIT 인코더. 정의·데이터 메시지와 14바이트 헤더, CRC를 써서 구조적으로
// 올바른 파일을 만든다.
// [왜 필요한가] 변조 테스트(TamperedFitTest, 5단계 H8)는 정상 파일에서 한 가지씩만 망가뜨려야 어떤 변조가
// 어떤 응답을 낳는지 특정할 수 있다. 바이트 구조를 아는 인코더로 만들면 크기 필드·CRC·정의 순서를 하나씩
// 정확히 조작할 수 있다.
// [연결] TamperedFitTest가 쓴다. CRC는 FitDecoder.crc16을 그대로 불러 디코더와 같은 계산을 한다.

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 테스트용 아주 작은 FIT 인코더: 전역 메시지마다 정의 하나, 리틀 엔디언, 개발자 필드 없음. 구조적으로
 * 올바른 파일을 만들어 테스트가 한 번에 한 가지씩만 변조할 수 있게 한다.
 */
class FitWriter {
    // data는 데이터 영역 바이트, definitions는 로컬 타입별 필드 정의(필드 번호, 크기, 기본 타입). data()가
    // 값마다 몇 바이트를 쓸지 정의에서 찾는다.
    private val data = ByteArrayOutputStream()
    private val definitions = HashMap<Int, List<Triple<Int, Int, Int>>>() // 로컬 타입 -> (필드 번호, 크기, 기본 타입)

    /**
     * 로컬 메시지 [local]을 전역 메시지 [global]로 정의하고 필드(필드 번호, 크기, 기본 타입)를 등록하는 정의
     * 메시지를 쓴다: 헤더 0x40|로컬 타입, 예약 0, 아키텍처 0(리틀 엔디언), 전역 번호 2바이트, 필드 수, 필드마다
     * 3바이트(번호, 크기, 기본 타입).
     */
    fun define(local: Int, global: Int, fields: List<Triple<Int, Int, Int>>): FitWriter {
        definitions[local] = fields
        data.write(0x40 or local)
        data.write(0) // 예약 바이트
        data.write(0) // 리틀 엔디언
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

    /**
     * 데이터 메시지를 쓴다. [values]는 필드 순서대로의 원시 정수다. 헤더(로컬 타입) 뒤에 값마다 정의된 크기만큼
     * 리틀 엔디언 바이트를 쓴다. 값을 8바이트 Long으로 쓴 뒤 앞에서부터 필요한 바이트만 잘라 쓴다(리틀
     * 엔디언이라 하위 바이트가 앞에 온다).
     */
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

    /** 정의를 거치지 않은 바이트를 데이터 영역에 그대로 붙인다(정의 없는 데이터, 잘린 정의처럼 일부러 망가뜨린 내용용). */
    fun raw(bytes: ByteArray): FitWriter {
        data.write(bytes)
        return this
    }

    /**
     * 전체 파일: 14바이트 헤더, 데이터, CRC. 헤더는 길이 14, 프로토콜 버전 0x20(2.0), 프로필 버전 2170, 데이터
     * 크기, '.FIT', 헤더 CRC 0(생략 표시)이다. 파일 CRC는 헤더 + 데이터 전체로 계산해 끝에 리틀 엔디언으로
     * 붙인다. [declaredDataSize]는 헤더의 크기 필드 값을 덮어써 크기만 속이고(변조 1·2), breakCrc는 CRC만
     * 바꾼다(변조 3).
     */
    fun build(declaredDataSize: Int? = null, breakCrc: Boolean = false): ByteArray {
        val body = data.toByteArray()
        val header = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
        header.put(14).put(0x20).putShort(2170).putInt(declaredDataSize ?: body.size).put(".FIT".toByteArray(Charsets.US_ASCII))
        header.putShort(0) // 헤더 CRC는 선택 사항이라 0(생략)
        val headerBytes = header.array()
        val all = headerBytes + body
        var crc = FitDecoder.crc16(all, 0, all.size)
        if (breakCrc) crc = crc xor 0x5555
        return all + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    companion object {
        const val FIT_EPOCH = FitDecoder.FIT_EPOCH_OFFSET

        /**
         * 정상 사이클링 라이딩: file_id(activity, 제조사 1 = garmin) + session(sport = cycling) + [count]개
         * record(1초 간격, 주어진 일정한 파워). backwards면 시각을 거꾸로 쓴다(변조 6). 기본 타입 0x84·0x86은
         * uint16(4)·uint32(6)에 엔디언 비트(0x80)를 켠 값이고, 시각은 FIT 기준 시각으로 바꿔 넣는다.
         */
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
