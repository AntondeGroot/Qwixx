package nl.adg.qwixx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class QwixxApplication extends SpringBootServletInitializer {

	public static void main(String[] args) {
		SpringApplication.run(QwixxApplication.class, args);
	}

	/** Entry point when the WAR is deployed into an external Tomcat instead of run with java -jar. */
	@Override
	protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
		return builder.sources(QwixxApplication.class);
	}

}
