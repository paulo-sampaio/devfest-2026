package io.kirimure.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication {
    public static final long BOOT_TIME_MS = System.currentTimeMillis();

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
