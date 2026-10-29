# Build the Spring Boot jar, then run it on a JRE image.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon -q dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon -q bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /src/build/libs/*.jar /app/app.jar
USER app
EXPOSE 8080
# JVM 실행 조건(2026-10-30, docs/experiments.md 5단계 'compose 스택의 서버 메모리', 규칙 변경 17).
# - GC를 적어 둔다(-XX:+UseSerialGC). JDK 21은 프로세서 2개 이상·메모리 1,792 MiB 이상일 때만 G1을 고르고 그 밖에는
#   SerialGC를 고른다. 컨테이너 안에서는 메모리 한도가 그 메모리라 compose의 768 MiB 한도에서는 적지 않아도 Serial이었다
#   (PrintFlagsFinal의 출처 {ergonomic}). 적어 두면 한도를 바꾸거나 다른 호스트에서 띄울 때 GC가 말없이 바뀌지 않는다.
#   G1으로 바꾸지 않은 것은 이 크기의 힙에서는 짧은 정지라는 이점이 작고, G1은 GC 자료 구조(표시 비트맵·기억 집합)에
#   네이티브 메모리를 더 쓰기 때문이다(G1은 이번에 재지 않았다. Serial의 GC 자료 구조는 NMT로 1.4 MiB였다).
# - 최대 힙은 컨테이너 메모리의 60%(768 MiB 한도에서 462 MiB). 75%(576 MiB)일 때 상한 가까운 FIT 업로드 동시 5개와 조회
#   10건에서 컨테이너가 메모리 한도에 닿았다(memory.peak = 한도, JVM 최대 RSS 747.7 MiB). 힙 밖(메타스페이스·코드 캐시·
#   JIT 작업 영역·스레드·C 할당)이 띄운 직후부터 약 170~190 MiB를 쓰기 때문이다. 60%에서는 한도에 닿지 않았지만
#   (최대 728.5 MiB) 미리 정한 기준(한도의 90%)은 넘었다(H13 기각). 한도나 비율을 더 바꾸는 것은 새 측정 규칙을 먼저
#   정한 뒤에 한다. 한도를 바꾸면 최대 힙도 같은 비율로 따라 바뀐다.
# - -XX:MaxMetaspaceSize는 두지 않는다. 메타스페이스는 약 53 MiB였고(NMT), 근거 없는 상한은 Metaspace OutOfMemoryError를
#   부를 수 있다.
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
