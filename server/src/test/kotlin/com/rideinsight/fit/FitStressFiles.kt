package com.rideinsight.fit

// [파일 역할] 업로드 자원 증폭을 재고 막는 테스트에 쓰는 '조작 FIT' 생성기. 구조적으로는 올바른 FIT지만,
// 파일 크기에 비해 디코더·서비스가 쓰는 메모리가 크게 불어나도록 만든 파일 세 종류를 만든다.
// [왜 필요한가] 업로드 한도(10 MB)는 바이트 수만 묶는다. 디코더가 메시지마다 객체를 만들면 작은 메시지를 아주
// 많이 넣거나(1) 필드가 아주 많은 메시지를 넣거나(2) 필드를 다 갖춘 record를 상한 가까이 넣은(3) 파일로 입력보다 수십~수백 배 큰 힙을 쓰게 할 수 있다. 실제로
// 얼마나 불어나는지 재고(FitMemoryProbe, docs/experiments.md 5단계 '업로드 자원 증폭'), 상한을 넣은 뒤
// 같은 파일이 정해 둔 응답 코드를 받는지 테스트한다(TamperedFitTest).
// [연결] FitWriter(테스트용 인코더)로 머리 부분을 만들고, 반복되는 메시지 본문은 바이트 배열로 한 번에 붙인다.
// 메시지 수백만 개를 FitWriter.data()로 하나씩 쓰면 느리기 때문이다.

object FitStressFiles {
    /**
     * 압축 타임스탬프 헤더의 기준이 되는 첫 record 시각(Unix 초). FIT 시각(1989-12-31 기준 초)의 하위 5비트가
     * 0이 되도록 맞춰, 뒤따르는 압축 헤더의 시각 오프셋(0~31)이 1초씩 늘며 32초마다 자연스럽게 넘어가게 한다.
     */
    val START_UNIX: Long = 1_600_000_000L - ((1_600_000_000L - FitDecoder.FIT_EPOCH_OFFSET) and 0x1F)

    /**
     * (1) 작은 record 메시지를 아주 많이 넣은 파일. file_id·session(사이클링)·전체 timestamp가 있는 첫 record 다음에
     * 압축 타임스탬프 헤더 1바이트 + 파워 1바이트(uint8)짜리 record를 이어 붙인다. 메시지 하나가 2바이트라
     * [totalMessages]개(머리 메시지 3개 포함)가 약 2 × [totalMessages] 바이트에 들어간다. 시각은 1초씩
     * 늘고(정상 1 Hz 라이딩처럼 보임), 파워는 150~249 W를 돈다.
     */
    fun tinyRecords(totalMessages: Int): ByteArray {
        require(totalMessages >= 4)
        val w = FitWriter()
        w.define(0, FitProfile.MSG_FILE_ID, listOf(Triple(FitProfile.FileId.TYPE, 1, 0x00), Triple(FitProfile.FileId.MANUFACTURER, 2, 0x84)))
        w.data(0, listOf(FitProfile.FileId.TYPE_ACTIVITY, 1L))
        w.define(1, FitProfile.MSG_SESSION, listOf(Triple(FitProfile.Session.SPORT, 1, 0x00)))
        w.data(1, listOf(FitProfile.Sport.CYCLING))
        // 첫 record는 전체 timestamp를 가져야 한다. 그래야 뒤의 압축 헤더가 시각을 복원할 수 있다.
        w.define(2, FitProfile.MSG_RECORD, listOf(Triple(FitProfile.FIELD_TIMESTAMP, 4, 0x86), Triple(FitProfile.Record.POWER, 1, 0x02)))
        val t0 = START_UNIX - FitDecoder.FIT_EPOCH_OFFSET
        w.data(2, listOf(t0, 200L))
        // 로컬 타입 3: 파워 1바이트만 있는 record. 압축 헤더는 로컬 타입 0~3만 가리킬 수 있다.
        w.define(3, FitProfile.MSG_RECORD, listOf(Triple(FitProfile.Record.POWER, 1, 0x02)))
        val n = totalMessages - 3
        val body = ByteArray(n * 2)
        for (i in 0 until n) {
            val t = t0 + 1 + i
            // 압축 헤더: 최상위 비트 1, 비트 5~6 로컬 타입(3), 비트 0~4 시각의 하위 5비트.
            body[2 * i] = (0x80 or (3 shl 5) or (t and 0x1F).toInt()).toByte()
            body[2 * i + 1] = (150 + i % 100).toByte()
        }
        w.raw(body)
        return w.build()
    }

    /**
     * (3) 필드를 다 갖춘 record를 [records]개 넣은 파일: 심박(uint8)·케이던스(uint8)·거리(uint32)·속도(uint16)·
     * 파워(uint16)·고도(uint16). 첫 record만 전체 timestamp를 갖고 나머지는 압축 타임스탬프 헤더라 record
     * 하나가 13바이트다(50만 개면 약 6.5 MB로 업로드 한도 안). 상한(record 50만 개) 안에서 서비스가 실제로
     * 끝까지 처리하는(지표 계산·저장) 가장 무거운 입력에 가깝다. 값은 1 Hz 라이딩처럼 천천히 변한다.
     */
    fun richRecords(records: Int): ByteArray {
        require(records >= 2)
        val w = FitWriter()
        w.define(0, FitProfile.MSG_FILE_ID, listOf(Triple(FitProfile.FileId.TYPE, 1, 0x00), Triple(FitProfile.FileId.MANUFACTURER, 2, 0x84)))
        w.data(0, listOf(FitProfile.FileId.TYPE_ACTIVITY, 1L))
        w.define(1, FitProfile.MSG_SESSION, listOf(Triple(FitProfile.Session.SPORT, 1, 0x00)))
        w.data(1, listOf(FitProfile.Sport.CYCLING))
        val fields = listOf(
            Triple(FitProfile.Record.HEART_RATE, 1, 0x02),
            Triple(FitProfile.Record.CADENCE, 1, 0x02),
            Triple(FitProfile.Record.DISTANCE, 4, 0x86),
            Triple(FitProfile.Record.SPEED, 2, 0x84),
            Triple(FitProfile.Record.POWER, 2, 0x84),
            Triple(FitProfile.Record.ALTITUDE, 2, 0x84),
        )
        w.define(2, FitProfile.MSG_RECORD, listOf(Triple(FitProfile.FIELD_TIMESTAMP, 4, 0x86)) + fields)
        val t0 = START_UNIX - FitDecoder.FIT_EPOCH_OFFSET
        fun values(i: Int) = listOf(
            (120 + i % 50).toLong(), (80 + i % 20).toLong(), (i * 800L), (8000 + i % 1000).toLong(),
            (180 + i % 120).toLong(), (2500 + i % 400).toLong(),
        )
        w.data(2, listOf(t0) + values(0))
        w.define(3, FitProfile.MSG_RECORD, fields)
        val n = records - 1
        val body = java.nio.ByteBuffer.allocate(n * 13).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (k in 0 until n) {
            val i = k + 1
            val v = values(i)
            body.put((0x80 or (3 shl 5) or ((t0 + i) and 0x1F).toInt()).toByte())
            body.put(v[0].toInt().toByte()).put(v[1].toInt().toByte()).putInt(v[2].toInt())
            body.putShort(v[3].toInt().toShort()).putShort(v[4].toInt().toShort()).putShort(v[5].toInt().toShort())
        }
        w.raw(body.array())
        return w.build()
    }

    /**
     * (2) 필드가 255개(각 1바이트, uint8)인 메시지를 [targetBytes] 가까이 채운 파일. 전역 메시지 번호는 프로필에
     * 없는 0xFF00이라 서비스는 쓰지 않는 메시지다. 디코더가 이런 메시지의 필드까지 모두 담으면 256바이트
     * 메시지 하나가 필드 255개짜리 맵이 된다. 앞부분은 정상 라이딩([records]초, FitWriter.ride)이라 디코딩만
     * 통과하면 저장까지 간다.
     */
    fun wideMessages(targetBytes: Int, records: Int = 60): ByteArray {
        val w = FitWriter.ride(records)
        w.define(3, 0xFF00, List(255) { Triple(it, 1, 0x02) })
        val head = w.build().size
        val count = (targetBytes - head) / 256
        val one = ByteArray(256) { if (it == 0) 3 else 1 }
        val body = ByteArray(count * 256)
        for (i in 0 until count) System.arraycopy(one, 0, body, i * 256, 256)
        w.raw(body)
        return w.build()
    }
}
