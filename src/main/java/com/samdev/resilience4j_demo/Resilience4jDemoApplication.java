package com.samdev.resilience4j_demo;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class Resilience4jDemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(Resilience4jDemoApplication.class, args);
	}

	@Bean
    CommandLineRunner testPaymentApi(RestTemplate restTemplate) {
		return args -> {
			String response = restTemplate.getForObject(
					"https://jsonplaceholder.typicode.com/posts/1",
					String.class
			);
			System.out.println("Payment API reachable: " + response);
		};
	}

}
