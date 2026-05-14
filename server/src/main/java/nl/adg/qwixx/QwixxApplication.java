package nl.adg.qwixx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class QwixxApplication {

	public static void main(String[] args) {
		SpringApplication.run(QwixxApplication.class, args);
	}

}
