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
		return PostgreSQLContainer(DockerImageName.parse("postgres:16"))
	}

}
