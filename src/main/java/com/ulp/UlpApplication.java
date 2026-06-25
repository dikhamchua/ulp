package com.ulp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the ULP (University Learning Platform) Spring Boot application.
 *
 * <p>This class bootstraps the Spring application context, triggers Flyway schema
 * migrations, and starts the embedded web server. All auto-configuration is
 * enabled via {@link SpringBootApplication}.
 *
 * <p>{@link EnableScheduling} is enabled so that scheduled tasks (e.g. the
 * import-session cleanup job in {@code com.ulp.classes.imports.ImportSessionStore})
 * are picked up by Spring's task scheduler.
 */
@SpringBootApplication
@EnableScheduling
public class UlpApplication {

	/**
	 * Launches the ULP application.
	 *
	 * @param args command-line arguments passed to {@link SpringApplication#run}
	 */
	public static void main(String[] args) {
		SpringApplication.run(UlpApplication.class, args);
	}

}
