package com.example.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling runs the outbox relay (REQ-DA-002). */
@SpringBootApplication
@EnableScheduling
public class ShopMallApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopMallApplication.class, args);
    }
}
