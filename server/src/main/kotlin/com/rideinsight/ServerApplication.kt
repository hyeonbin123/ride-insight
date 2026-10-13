package com.rideinsight

// [파일 역할] Spring Boot 서버의 진입점이다. 이 클래스가 있는 com.rideinsight 패키지 아래(api, service,
// storage)의 @RestController·@Service·@Repository·@Component를 찾아 등록하고, 자동 설정으로 DB 연결·Flyway
// 마이그레이션·웹 서버를 띄운다.
// [연결] build.gradle.kts의 springBoot.mainClass가 이 파일의 main(클래스 이름 ServerApplicationKt)을
// 가리킨다. tools/ImportOpenData.kt는 이 클래스로 웹 서버 없는 컨텍스트를 띄우고, 테스트의
// TestServerApplication은 이 클래스에 Testcontainers 설정을 더해 실행한다.

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// 설정·구성 요소 스캔·자동 설정의 기준점이 되는 빈 클래스. 내용이 없어도 애너테이션만으로 역할을 한다.
@SpringBootApplication
class ServerApplication

// `./gradlew bootRun`과 `java -jar app.jar`(server/Dockerfile)가 호출하는 main. 명령줄 인자를 Spring에 그대로
// 넘겨 --server.port 같은 설정을 실행 시 바꿀 수 있다.
fun main(args: Array<String>) {
	runApplication<ServerApplication>(*args)
}
