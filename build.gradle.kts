import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.pally"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

extra["testcontainersVersion"] = "1.20.4"

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Database
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // AWS S3 SDK
    implementation("software.amazon.awssdk:s3:2.26.25")

    // PDF text extraction
    implementation("org.apache.pdfbox:pdfbox:3.0.2")

    // Tesseract OCR
    implementation("net.sourceforge.tess4j:tess4j:5.11.0")

    // OpenAPI / SpringDoc
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Jackson extras
    implementation("com.fasterxml.jackson.module:jackson-module-parameter-names")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Pass Docker socket to forked test JVM for Testcontainers on macOS Docker Desktop
    val dockerSock = System.getenv("DOCKER_HOST")
        ?: "unix:///Users/${System.getProperty("user.name")}/.docker/run/docker.sock"
    environment("DOCKER_HOST", dockerSock)
    environment("DOCKER_API_VERSION", "1.44")
    jvmArgs(
        "-Ddocker.host=$dockerSock",
        "-Ddocker.io.apiVersion=1.44",
        "-DDOCKER_API_VERSION=1.44",
        "-Dapi.version=1.44",
    )
}

tasks.withType<BootJar> {
    archiveFileName.set("pally-backend.jar")
}
