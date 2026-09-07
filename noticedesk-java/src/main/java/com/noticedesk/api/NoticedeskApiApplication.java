package com.noticedesk.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.noticedesk.api.config.AppProperties;

import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
@EnableTransactionManagement
public class NoticedeskApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(NoticedeskApiApplication.class, args);
    }
}
