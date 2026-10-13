package com.rideinsight

// [파일 역할] 테스트 소스에 둔 개발용 실행 진입점. 실제 서버(ServerApplication) 설정에
// TestcontainersConfiguration을 더해, Docker로 PostgreSQL 컨테이너를 띄운 채 서버를 실행한다. 테스트 소스라
// 배포 jar에는 들어가지 않는다.

import org.springframework.boot.fromApplication
import org.springframework.boot.with


// fromApplication<ServerApplication>().with(...): 원래 앱에 테스트 설정 클래스를 더해 실행한다. 평소 개발
// 실행은 bootRun + compose 지원(server/compose.yaml)을 쓴다(README 실행).
fun main(args: Array<String>) {
	fromApplication<ServerApplication>().with(TestcontainersConfiguration::class).run(*args)
}
