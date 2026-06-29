package com.samdev.resilience4j_demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        // How long to wait to establish a connection — fail fast if server unreachable
        factory.setConnectTimeout(3000);

        // How long to wait for data after connection is established
        // This is what triggers a slow call in Resilience4j's slowCallDurationThreshold
        factory.setReadTimeout(5000);

        return new RestTemplate(factory);
    }
}