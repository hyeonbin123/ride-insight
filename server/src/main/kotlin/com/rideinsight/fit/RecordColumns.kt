package com.rideinsight.fit

// [파일 역할] FIT record 메시지(한 시점의 측정값)를 메시지 객체 대신 필드별 열(원시 배열)로 담는 저장소다.
// 디코더(FitDecoder)가 record를 읽을 때마다 여기에 원시 값(배율 적용 전)을 쌓고, FitActivity가 배율을 적용해
// 시계열로 바꾼다.
// [설계 이유] record는 파일 하나에 수십만 개가 올 수 있는 유일한 메시지다. 메시지마다 객체와 배열을 만들면
// 메시지당 백수십 바이트가 들어, record 50만 개짜리 6.5 MB 파일 하나가 디코딩에만 80 MB 넘는 힙을 붙잡았다
// (2026-10-15 측정, docs/experiments.md 5단계 '업로드 자원 증폭'). 열로 담으면 값 하나에 8바이트만 든다.
// 열은 고정 크기 조각(chunk)으로 늘려, 배열을 두 배로 키우며 복사할 때 옛 배열과 새 배열이 함께 살아 있는
// 순간이 생기지 않게 했다. 한 번도 값이 없던 필드는 조각을 만들지 않는다.
// [담는 필드] FitProfile.KEPT_FIELDS의 record 필드와 timestamp(253)뿐이다. 그 밖의 필드는 디코더가 건너뛴다.

/**
 * record 메시지들의 원시 필드 값을 파일 순서대로 담은 열 저장소. [size]는 record 수, [int]·[double]은 i번째
 * record의 필드 값(없으면 null)이다. 정수 값은 [Long], 실수 타입(float32/64)으로 정의된 필드의 값은 [Double]로
 * 따로 담고, [double]은 실수 값이 없으면 정수 값을 실수로 바꿔 준다(FitMessage와 같은 규칙).
 */
class RecordColumns private constructor(
    val size: Int,
    private val intChunks: Array<Array<LongArray?>>,
    private val doubleChunks: Array<Array<DoubleArray?>>,
) {
    /** i번째 record의 정수 필드 값(없으면 null). 담지 않는 필드 번호도 null이다. */
    fun int(index: Int, field: Int): Long? {
        val c = COLUMN_OF.getOrElse(field) { -1 }
        if (c < 0 || index !in 0 until size) return null
        val chunk = intChunks[c].getOrNull(index / CHUNK) ?: return null
        val v = chunk[index % CHUNK]
        return if (v == ABSENT_LONG) null else v
    }

    /** i번째 record의 실수 필드 값. 실수로 정의된 값이 없으면 정수 값을 실수로 바꿔 돌려준다(없으면 null). */
    fun double(index: Int, field: Int): Double? {
        val c = COLUMN_OF.getOrElse(field) { -1 }
        if (c < 0 || index !in 0 until size) return null
        val chunk = doubleChunks[c].getOrNull(index / CHUNK)
        if (chunk != null) {
            val v = chunk[index % CHUNK]
            if (!v.isNaN()) return v
        }
        return int(index, field)?.toDouble()
    }

    /** FIT 배율·오프셋을 적용한 값: `raw / scale - offset`(FitMessage.scaled와 같은 식). */
    fun scaled(index: Int, field: Int, scale: Double, offset: Double = 0.0): Double? =
        double(index, field)?.let { it / scale - offset }

    companion object {
        // 조각 하나의 칸 수. 8,192칸 × 8바이트 = 64 KB라 조각 수가 많지 않고, 마지막 조각의 빈칸도 작다.
        private const val CHUNK = 8192

        // '값 없음' 표시. 정수 열은 Long.MIN_VALUE(FIT 정수 필드는 64비트 부호 있는 최솟값을 무효 값으로 쓰지
        // 않으므로 실제 값과 겹칠 가능성이 사실상 없다), 실수 열은 NaN이다. 실수 필드에 NaN 비트열이 들어오면
        // 값이 없는 것으로 본다(전에는 NaN 그대로 담았지만 저장할 때 NULL로 바뀌었으므로 결과는 같다).
        private const val ABSENT_LONG = Long.MIN_VALUE

        // 담는 필드 번호 목록(열 순서)과, 필드 번호(0~255) → 열 번호 표(-1이면 담지 않음).
        private val FIELDS: IntArray =
            (FitProfile.KEPT_FIELDS.getValue(FitProfile.MSG_RECORD) + FitProfile.FIELD_TIMESTAMP).sorted().toIntArray()
        private val COLUMN_OF: IntArray = IntArray(256) { -1 }.also { table -> FIELDS.forEachIndexed { i, f -> table[f] = i } }
    }

    /**
     * 디코더가 쓰는 쌓기 도구. [add]는 record 하나의 (필드 번호, 값) 목록을 받아 다음 행에 쓴다. 다 쌓으면
     * [build]로 읽기 전용 [RecordColumns]를 만든다.
     */
    class Builder {
        private var size = 0
        private val ints = Array(FIELDS.size) { ArrayList<LongArray?>() }
        private val doubles = Array(FIELDS.size) { ArrayList<DoubleArray?>() }

        /**
         * 다음 행에 record 하나를 쓴다. 입력은 디코더의 작업 공간 배열 그대로다: 정수 필드 번호·값 앞
         * [intCount]개, 실수 필드 번호·값 앞 [doubleCount]개. 담지 않는 필드 번호는 무시한다.
         */
        fun add(intKeys: IntArray, intValues: LongArray, intCount: Int, doubleKeys: IntArray, doubleValues: DoubleArray, doubleCount: Int) {
            val row = size++
            for (k in 0 until intCount) {
                val c = COLUMN_OF.getOrElse(intKeys[k]) { -1 }
                if (c >= 0) intSlot(c, row)[row % CHUNK] = intValues[k]
            }
            for (k in 0 until doubleCount) {
                val c = COLUMN_OF.getOrElse(doubleKeys[k]) { -1 }
                if (c >= 0) doubleSlot(c, row)[row % CHUNK] = doubleValues[k]
            }
        }

        // row가 들어갈 조각을 돌려준다. 그 열에 처음 값이 들어오는 조각이면 '값 없음'으로 채워 새로 만들고, 그
        // 앞의 빈 조각 자리는 null로 둔다(값이 한 번도 없던 구간은 메모리를 쓰지 않는다).
        private fun intSlot(column: Int, row: Int): LongArray {
            val chunks = ints[column]
            val index = row / CHUNK
            while (chunks.size <= index) chunks.add(null)
            return chunks[index] ?: LongArray(CHUNK).also { it.fill(ABSENT_LONG); chunks[index] = it }
        }

        private fun doubleSlot(column: Int, row: Int): DoubleArray {
            val chunks = doubles[column]
            val index = row / CHUNK
            while (chunks.size <= index) chunks.add(null)
            return chunks[index] ?: DoubleArray(CHUNK).also { it.fill(Double.NaN); chunks[index] = it }
        }

        fun build(): RecordColumns = RecordColumns(
            size,
            Array(FIELDS.size) { ints[it].toTypedArray() },
            Array(FIELDS.size) { doubles[it].toTypedArray() },
        )
    }
}
