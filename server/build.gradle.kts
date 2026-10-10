plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.rideinsight"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
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

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Offline parity tool: computes metrics from OpenData zips and writes JSONL for ml/ to analyse.
// Usage: ./gradlew -q parity --args="--zips ../data/opendata --index ../data/opendata_index --athletes ../manifests/parity_dev.txt --out ../reports/parity/dev.jsonl"
tasks.register<JavaExec>("parity") {
	group = "tools"
	description = "Compute metrics for OpenData athletes and compare with GoldenCheetah metadata"
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set("com.rideinsight.tools.ParityToolKt")
}

// Import one OpenData athlete into the database (needs the PostgreSQL from compose.yaml running).
// Usage: ./gradlew -q importOpenData --args="--zip ../data/opendata/<id>.zip --index ../data/opendata_index/<id>.json --name Demo"
tasks.register<JavaExec>("importOpenData") {
	group = "tools"
	description = "Import a GoldenCheetah OpenData athlete as activities and thresholds"
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set("com.rideinsight.tools.ImportOpenDataKt")
}
