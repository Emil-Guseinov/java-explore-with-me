package ru.practicum.stats.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "ru.practicum.stats.server")
public class StatsApplication {
    public static void main(String[] args) {
        SpringApplication.run(StatsApplication.class, args);
    }
}
