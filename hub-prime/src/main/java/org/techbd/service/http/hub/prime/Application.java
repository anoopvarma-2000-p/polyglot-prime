package org.techbd.service.http.hub.prime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
	scanBasePackages = { "org.techbd" },
	excludeName = {
		"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
		"org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
		"org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration"
	}
)
// Temporarily commented out for Spring Boot 4.x upgrade - JPA not actively used, JOOQ is used instead
// @EnableJpaRepositories(basePackages = "org.techbd.udi")
@EnableCaching
@EnableScheduling
@EnableConfigurationProperties
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

}
