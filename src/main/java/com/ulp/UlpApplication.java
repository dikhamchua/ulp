package com.ulp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the ULP (University Learning Platform) Spring Boot application.
 *
 * <p>This class bootstraps the Spring application context, triggers Flyway schema
 * migrations, and starts the embedded web server. All auto-configuration is
 * enabled via {@link SpringBootApplication}.
 */
@SpringBootApplication
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
