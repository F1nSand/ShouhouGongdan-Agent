package com.aftercare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AftersalesApplication {

    public static void main(String[] args) {
        SpringApplication.run(AftersalesApplication.class, args);
    }
}
