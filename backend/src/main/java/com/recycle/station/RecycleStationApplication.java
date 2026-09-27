package com.recycle.station;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class RecycleStationApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecycleStationApplication.class, args);
    }
}
