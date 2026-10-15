// [파일 역할] 서버(Kotlin + Spring Boot) 프로젝트의 Gradle 빌드 설정이다. 플러그인과 JDK 버전, 의존성, 테스트
// 실행 방식, 그리고 서버를 띄우지 않고 돌리는 오프라인 도구 태스크 두 개(parity, importOpenData)를 정의한다.
// [설계 이유] Spring Initializr가 만든 뼈대(Kotlin 2.3.21, Spring Boot 4.1.1, JDK 21, Gradle 9.7.1
// wrapper)에서 출발했고(커밋 3b43596), 첫 커밋에 있던 JPA(data-jpa)를 빼고 spring-boot-starter-jdbc(JdbcClient)
// + Flyway로 바꿨다. 초 단위 시계열을 PostgreSQL 배열 컬럼에 저장하는 데 ORM이 방해가 되기 때문이다(docs/api.md
// 2단계).
// [연결] `./gradlew test`(README 테스트 명령, GitHub Actions의 server 잡), `./gradlew bootRun`(로컬 서버),
// server/Dockerfile의 `bootJar`가 이 설정을 쓴다.

plugins {
	// - kotlin("jvm"): Kotlin 소스를 JVM 바이트코드로 컴파일한다.
	// - kotlin("plugin.spring"): Kotlin 클래스는 기본이 final인데, Spring이 @Service·@Transactional 등이 붙은
	//   클래스에 프록시를 만들 수 있도록 그런 클래스를 자동으로 open으로 만든다.
	// - org.springframework.boot: bootRun(개발 실행)과 bootJar(실행 가능한 jar) 태스크를 준다.
	// - io.spring.dependency-management: Spring Boot BOM으로 라이브러리 버전을 맞춰, 아래 dependencies에 버전을
	//   적지 않아도 되게 한다.
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

// 산출물 좌표. 그룹은 코드 패키지 이름(com.rideinsight)과 맞췄다.
group = "com.rideinsight"
version = "0.0.1-SNAPSHOT"

// Java toolchain을 21로 고정해 어떤 JDK로 Gradle을 실행하든 JDK 21로 컴파일한다. CI(temurin 21)와 Docker 빌드
// 이미지(eclipse-temurin:21)도 같은 주 버전을 쓴다.
java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

// 모든 라이브러리는 Maven Central에서 받는다.
repositories {
	mavenCentral()
}

dependencies {
	// 실행 의존성
	// - actuator: GET /actuator/health를 제공한다. docs/api.md 완료 기준 5(compose로 띄운 서버가 UP)와 통합
	//   테스트가 이 엔드포인트를 확인한다. 노출 범위는 application.properties에서 health·info로 제한한다.
	// - starter-jdbc: JdbcClient로 SQL을 직접 쓴다(JPA 대신, 배열 컬럼 바인딩을 직접 다루기 위해).
	// - starter-flyway + flyway-database-postgresql: 앱 시작 때 db/migration/V1__init.sql로 스키마를 만든다.
	//   Flyway 10부터 PostgreSQL 지원이 별도 모듈로 나뉘어 둘 다 필요하다.
	// - starter-validation: 요청 DTO의 @NotBlank·@Min 같은 Bean Validation 애너테이션을 검사한다(api/Dtos.kt).
	// - starter-webmvc: 서블릿 기반(동기) REST 컨트롤러. 비동기(WebFlux)는 쓰지 않는다.
	// - kotlin-reflect, jackson-module-kotlin: Jackson 3(tools.jackson 패키지)이 Kotlin data class를 생성자 인자
	//   이름으로 JSON과 주고받게 한다.
	// - spring-boot-docker-compose(developmentOnly): bootRun 때 server/compose.yaml의 postgres:16 컨테이너를
	//   자동으로 띄우고 접속 정보를 채운다. 개발 실행 전용이라 bootJar에는 들어가지 않는다.
	// - postgresql(runtimeOnly): PostgreSQL JDBC 드라이버. 코드에서 직접 참조하지 않아 실행 시점에만 둔다.
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("org.postgresql:postgresql")
	// 테스트 의존성: 기능별 Spring Boot 테스트 지원, Testcontainers(Docker로 실제 PostgreSQL 16 컨테이너를 띄워
	// API 통합 테스트를 돌린다. TestcontainersConfiguration·ApiTestBase), kotlin-test(JUnit 5 위의 assert 함수).
	// Testcontainers 테스트는 Docker가 켜져 있어야 한다(README).
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// bootRun·bootJar가 실행할 main을 서버(ServerApplication.kt의 main → 클래스 이름 ServerApplicationKt)로
// 고정한다. tools 패키지의 ParityTool·ImportOpenData에도 main 함수가 있어, 지정하지 않으면 bootRun이 어느
// main을 쓸지 정하지 못했다(docs/ai-verification.md 2단계 실제 실행).
springBoot {
	// tools 패키지에도 main 함수가 따로 있으므로, bootRun은 반드시 서버를 띄우도록 지정한다.
	mainClass.set("com.rideinsight.ServerApplicationKt")
}

// Kotlin 컴파일러 옵션(Initializr 기본값).
// - -Xjsr305=strict: Spring API의 null 관련 애너테이션을 Kotlin 타입의 nullable 여부로 엄격하게 반영해,
//   null이 올 수 있는 값을 컴파일 단계에서 드러낸다.
// - -Xannotation-default-target=param-property: 생성자 프로퍼티에 단 애너테이션(예: Dtos.kt의 @NotBlank,
//   @Min)이 생성자 파라미터뿐 아니라 프로퍼티에도 적용되게 한다.
kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

// 모든 테스트 태스크를 JUnit 5 플랫폼으로 실행한다(kotlin-test-junit5).
tasks.withType<Test> {
	useJUnitPlatform()
}

// parity 태스크: 1단계(지표 정확성)용 오프라인 도구 tools/ParityTool.kt를 실행한다. GoldenCheetah OpenData
// 선수 zip과 ml/이 만든 라이딩 인덱스를 읽어, 이 저장소의 NP·IF·TSS·MMP 계산 결과를 GoldenCheetah 메타데이터
// 값과 나란히 JSONL로 쓴다. 분석은 ml/의 parity.py가 하고, 3단계 FTP 실험의 라이딩
// 특징(data/features/all.jsonl)도 이 태스크로 내보냈다(docs/experiments.md 3단계 데이터).
// JavaExec는 런타임 클래스패스로 지정한 main 클래스만 실행하므로 웹 서버나 DB 없이 돈다.
// 사용법: ./gradlew -q parity --args="--zips ../data/opendata --index ../data/opendata_index --athletes ../manifests/parity_dev.txt --out ../reports/parity/dev.jsonl"
tasks.register<JavaExec>("parity") {
	group = "tools"
	description = "Compute metrics for OpenData athletes and compare with GoldenCheetah metadata"
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set("com.rideinsight.tools.ParityToolKt")
}

// importOpenData 태스크: OpenData 선수 1명을 DB에 활동과 FTP 이력으로 넣는 tools/ImportOpenData.kt를
// 실행한다(docs/api.md OpenData 가져오기, 화면 데모용). 이 도구는 Spring 컨텍스트를 웹 서버 없이 띄워
// 저장소·지표 서비스 빈을 재사용하므로 PostgreSQL(compose.yaml로 띄운 것)이 떠 있어야 한다.
// 사용법: ./gradlew -q importOpenData --args="--zip ../data/opendata/<id>.zip --index ../data/opendata_index/<id>.json --name Demo"
tasks.register<JavaExec>("importOpenData") {
	group = "tools"
	description = "Import a GoldenCheetah OpenData athlete as activities and thresholds"
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set("com.rideinsight.tools.ImportOpenDataKt")
}

// fitMemoryProbe 태스크: FIT 디코딩의 힙 사용량을 재는 측정 도구(src/test/kotlin/.../fit/FitMemoryProbe.kt)를
// 실행한다. 조작 파일 생성기(FitStressFiles)와 테스트용 인코더(FitWriter)가 테스트 소스에 있어 테스트 클래스패스로
// 돈다. 200만 메시지 파일을 지금 구현으로 디코딩해도 끝까지 잴 수 있게 힙을 4 GB로 연다(서버 실행 설정과는
// 무관하다). 결과는 docs/experiments.md 5단계 '업로드 자원 증폭'.
// 사용법: ./gradlew -q fitMemoryProbe --args="--out ../work/fit-probe"
tasks.register<JavaExec>("fitMemoryProbe") {
	group = "tools"
	description = "Measure heap used by FIT decoding for normal and crafted files"
	classpath = sourceSets.test.get().runtimeClasspath
	mainClass.set("com.rideinsight.fit.FitMemoryProbeKt")
	jvmArgs("-Xmx4g", "-XX:+UseG1GC")
}
