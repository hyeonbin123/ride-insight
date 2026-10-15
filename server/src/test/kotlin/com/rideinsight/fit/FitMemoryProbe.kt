package com.rideinsight.fit

// [파일 역할] FIT 디코딩이 입력 크기에 비해 힙을 얼마나 쓰는지 재는 측정 도구(테스트 소스에 둔 main). 정상
// 샘플(Wahoo ELEMNT, Garmin Edge 810)과 조작 파일(FitStressFiles: 작은 record 다수, 255필드 메시지 다수)을
// 같은 JVM에서 차례로 디코딩해, 단계별로 (1) 새로 할당한 바이트 수와 (2) 결과 객체가 붙잡고 있는 힙(유지 크기)을
// 잰다. 조작 파일은 서버 업로드 측정(ml/.../load.py)에 쓰도록 출력 폴더에 함께 쓴다.
// [왜 이렇게 쟀나] 할당 바이트는 com.sun.management.ThreadMXBean의 스레드별 누적 할당량 차이로 잰다(GC 시점에
// 흔들리지 않는다). 유지 크기는 System.gc()를 여러 번 부른 뒤의 사용 힙 차이로 잰다(결과를 붙잡은 상태 − 놓은
// 상태). 둘 다 근삿값이지만 메시지 수를 바꿔 가며 재면 메시지당 바이트가 선형인지 확인할 수 있다.
// [실행] cd server && ./gradlew -q fitMemoryProbe --args="--out ../work/fit-probe"
//   결과 JSON은 <out>/probe.json, 조작 파일은 <out>/*.fit. 수치는 docs/experiments.md 5단계 '업로드 자원 증폭'.
// [주의] 단위 테스트가 아니다(측정값은 JVM·GC 설정에 따라 달라진다). Gradle 태스크가 -Xmx4g로 띄운다.

import java.io.File
import java.lang.management.ManagementFactory

// 측정 하나의 결과. retained는 결과 객체를 붙잡고 있을 때의 사용 힙 증가분(바이트), allocated는 그 단계에서
// 새로 할당한 바이트 수다.
private class StageResult(val millis: Long, val allocated: Long, val retained: Long)

// 현재 스레드가 지금까지 할당한 바이트 수(HotSpot 확장 API).
private fun allocatedBytes(): Long =
    (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).currentThreadAllocatedBytes

// 쓰레기를 치운 뒤의 사용 힙. 한 번의 System.gc()로는 다 치워지지 않을 수 있어 세 번 부른다.
private fun usedAfterGc(): Long {
    repeat(3) {
        System.gc()
        Thread.sleep(30)
    }
    return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
}

// block을 실행해 시간·할당량을 재고, 결과를 붙잡은 채 사용 힙을 잰 뒤 결과를 돌려준다. 유지 크기 = (결과를
// 붙잡은 상태의 사용 힙) − (실행 전 사용 힙).
private fun <T> measure(block: () -> T): Pair<T, StageResult> {
    val before = usedAfterGc()
    val a0 = allocatedBytes()
    val t0 = System.nanoTime()
    val result = block()
    val millis = (System.nanoTime() - t0) / 1_000_000
    val allocated = allocatedBytes() - a0
    val held = usedAfterGc()
    return result to StageResult(millis, allocated, held - before)
}

fun main(args: Array<String>) {
    // --out 폴더(기본 ../work/fit-probe). 조작 파일과 probe.json이 여기에 생긴다.
    val out = File(args.toList().let { a -> a.getOrNull(a.indexOf("--out") + 1)?.takeIf { a.contains("--out") } } ?: "../work/fit-probe")
    out.mkdirs()
    val resources = File("src/test/resources/fit")
    // 측정 대상: 정상 샘플 둘, 작은 record 다수(메시지 수 50만·100만·200만; 200만은 지금 디코더의 메시지 상한),
    // 255필드 메시지로 10 MB 가까이 채운 파일.
    val cases = linkedMapOf<String, () -> ByteArray>(
        "elemnt" to { File(resources, "2019-02-17-062644-ELEMNT-297E-195-0.fit").readBytes() },
        "edge810" to { File(resources, "Edge810-Vector-2013-08-16-15-35-10.fit").readBytes() },
        "tiny-records-500k" to { FitStressFiles.tinyRecords(500_000) },
        "tiny-records-1m" to { FitStressFiles.tinyRecords(1_000_000) },
        "tiny-records-2m" to { FitStressFiles.tinyRecords(2_000_000) },
        "wide-255-fields-10mb" to { FitStressFiles.wideMessages(10_000_000) },
        "rich-records-500k" to { FitStressFiles.richRecords(500_000) },
    )
    val rows = ArrayList<String>()
    for ((name, make) in cases) {
        val bytes = make()
        if (name.startsWith("tiny") || name.startsWith("wide") || name.startsWith("rich")) File(out, "$name.fit").writeBytes(bytes)
        // 디코딩(바이트 → 메시지 목록)과 활동 변환(메시지 → record 시계열)을 따로 잰다. 두 번째 단계의 유지
        // 크기는 FitFile을 놓은 뒤 FitActivity만 붙잡았을 때의 값이다.
        // FitActivity.from이 끝나는 순간에는 FitFile과 FitActivity가 함께 살아 있으므로, 두 유지 크기의 합이 이
        // 단계까지의 최대 유지 힙(peak_retained)이다. 서버의 업로드 처리는 여기에 시계열 목록·배열 리터럴 문자열을
        // 더 만든다(그 몫은 서버 측정에서 본다).
        val row = try {
            val (file, dec) = measure { FitDecoder.decode(bytes) }
            val messages = file.messages.size
            val (activity, act) = measure { FitActivity.from(file) }
            val records = activity.records.size
            val peak = dec.retained + act.retained
            """{"case":"$name","file_bytes":${bytes.size},"messages":$messages,"records":$records,""" +
                """"decode_ms":${dec.millis},"decode_allocated":${dec.allocated},"decode_retained":${dec.retained},""" +
                """"activity_ms":${act.millis},"activity_allocated":${act.allocated},"activity_retained":${act.retained},""" +
                """"peak_retained":$peak,"peak_retained_per_message":${peak / maxOf(messages, 1)},""" +
                """"peak_retained_over_file":${"%.1f".format(peak.toDouble() / bytes.size)}}"""
        } catch (e: FitDecodeException) {
            """{"case":"$name","file_bytes":${bytes.size},"rejected":"${e.message?.replace("\"", "'")}"}"""
        } catch (e: OutOfMemoryError) {
            """{"case":"$name","file_bytes":${bytes.size},"out_of_memory":true}"""
        }
        println(row)
        rows.add(row)
    }
    val maxHeap = Runtime.getRuntime().maxMemory()
    File(out, "probe.json").writeText(
        """{"jvm":"${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}","max_heap":$maxHeap,"cases":[""" +
            rows.joinToString(",\n") + "]}\n",
    )
}
