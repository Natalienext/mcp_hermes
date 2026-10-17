package org.example.wardrobe.memory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WardrobeMemoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(WardrobeMemoryApplication.class, args);
    }
}
