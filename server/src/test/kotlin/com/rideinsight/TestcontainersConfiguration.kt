package com.rideinsight

// [파일 역할] 통합 테스트용 PostgreSQL 컨테이너 설정. @ServiceConnection이 컨테이너의 JDBC URL·계정을
// Spring의 DataSource 설정으로 자동 연결하므로, 테스트는 application.properties의 접속 정보를 쓰지 않고,
// 테스트 컨텍스트마다 새 컨테이너의 빈 DB에 Flyway 마이그레이션부터 적용한다.
// [연결] ApiTestBase가 @Import로, TestServerApplication이 with(...)로 가져온다. 실행하려면 Docker가 켜져
// 있어야 한다(README 테스트).

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration

// proxyBeanMethods = false: @Bean 메서드 호출을 프록시로 가로채지 않는 가벼운 설정 클래스.
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	// 컨테이너도 Spring 빈이라 컨텍스트가 시작될 때 뜨고 닫힐 때 내려간다. 같은 설정을 쓰는 테스트
	// 클래스(ApiIntegrationTest, TamperedFitTest)는 Spring 테스트 컨텍스트 캐시로 컨텍스트와 컨테이너를 함께
	// 쓴다.
	@Bean
	@ServiceConnection
	fun postgresContainer(): PostgreSQLContainer {
		// compose.yaml과 같은 주 버전(16)을 써서 테스트와 로컬 서비스의 동작이 어긋나지 않게 한다.
		// 시작 대기 상한(2026-11-05 추가): Testcontainers 2.0.5의 PostgreSQLContainer는 기본으로 'database system
		// is ready to accept connections' 로그가 두 번(postgres 이미지가 초기화용 임시 서버를 띄웠다 내린 뒤 실제
		// 서버를 띄울 때마다 한 번씩) 나오기를 60초까지 기다린다(라이브러리 바이트코드로 확인). 다른 프로그램이
		// CPU·메모리를 많이 쓰는 개발 PC에서는 `docker run postgres:16`의 첫 준비 로그까지 약 52초가 걸린 적이 있어,
		// 두 번째 로그가 60초 안에 나오지 않아 테스트 컨텍스트 로드가 실패하는 일이 여러 번 있었다. 그때마다 이 값을
		// 4~5분으로 잠시 늘려 돌리면 통과했으므로 5분으로 고정한다. GitHub Actions 러너에서는 기본 60초로도 늘
		// 통과했고, 이 값은 대기 상한일 뿐이라 테스트 판정·서버 코드·실행 jar에는 영향이 없다.
		// 대가: Docker가 정말 고장 나 컨테이너가 뜨지 않으면 실패가 60초가 아니라 최대 5분 뒤에 드러난다.
		// 환경 변수로 상한을 받는 방법도 있지만, 테스트 전용 값 하나에 설정 경로를 늘릴 만큼의 이득이 없어 고정값으로
		// 둔다. 로그 대기 뒤의 JDBC 접속 확인에는 따로 상한(JdbcDatabaseContainer의 startupTimeoutSeconds, 기본 120초)이
		// 있고 여기서 바꾸지 않는다.
		return PostgreSQLContainer(DockerImageName.parse("postgres:16"))
			.withStartupTimeout(Duration.ofMinutes(5))
	}

}
