package com.depresolver;

import com.depresolver.config.ServiceUserProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ServiceUserProperties.class)
public class ResolverApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResolverApplication.class, args);
    }
}
