package com.example.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 스케줄링으로 outbox relay를 실행한다 (REQ-DA-002). */
@SpringBootApplication
@EnableScheduling
public class ShopMallApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopMallApplication.class, args);
    }
}
