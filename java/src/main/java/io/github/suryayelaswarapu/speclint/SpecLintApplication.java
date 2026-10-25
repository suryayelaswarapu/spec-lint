package io.github.suryayelaswarapu.speclint;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the spec-lint web service (REST API + web UI).
 *
 * <ul>
 *   <li>IDE: run this class.</li>
 *   <li>Gradle: {@code ./gradlew :java:bootRun}</li>
 *   <li>Jar: {@code ./gradlew :java:bootJar && java -jar java/build/libs/speclint.jar}</li>
 * </ul>
 *
 * <p>Then open http://localhost:8080. The command-line tool lives in {@link Main}.
 */
@SpringBootApplication
public class SpecLintApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpecLintApplication.class, args);
    }
}
