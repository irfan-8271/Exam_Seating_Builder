package com.examseating;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Spring Boot application.
 *
 * <p>How Spring Boot boots this project (very briefly):
 * <ol>
 *   <li>{@code SpringApplication.run(...)} builds the {@code ApplicationContext}
 *       (Spring's big bean container).</li>
 *   <li>{@code @SpringBootApplication} is a shorthand for three annotations:
 *       {@code @Configuration} (this class can define beans),
 *       {@code @EnableAutoConfiguration} (Spring Boot wires up sensible defaults
 *       from the classpath: DataSource from application.yml + mysql driver,
 *       Hibernate/JPA, Jackson for JSON, an embedded web server, security defaults...),
 *       and {@code @ComponentScan} (scans the {@code com.examseating} package for
 *       {@code @Component}/{@code @Service}/{@code @Repository}/{@code @Controller} classes
 *       and registers them as beans).</li>
 *   <li>Because {@code spring-boot-starter-web} is on the classpath, Boot starts an
 *       embedded Tomcat that listens on {@code server.port} (default 8080) and routes
 *       HTTP requests to our {@code @RestController}s.</li>
 *   <li>Because {@code spring-boot-starter-data-jpa} is present, Boot auto-configures
 *       Hibernate as the JPA provider, creates a Spring-managed {@code EntityManagerFactory}
 *       from the DataSource, and enables Spring Data JPA repositories
 *       ({@code @EnableJpaRepositories} is applied automatically to interfaces that extend
 *       {@code JpaRepository}).</li>
 * </ol>
 */
@SpringBootApplication
public class ExamSeatingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExamSeatingApplication.class, args);
    }
}
