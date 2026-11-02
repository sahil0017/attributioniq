package com.attributioniq.attribution_iq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AttributionIqApplication {

	public static void main(String[] args) {
		SpringApplication.run(AttributionIqApplication.class, args);
	}

}
