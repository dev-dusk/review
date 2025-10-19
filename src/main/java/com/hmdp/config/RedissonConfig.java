package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RedissonConfig {

    @Primary
    @Bean
    public RedissonClient redissonClient1() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://120.27.236.84:6379")
                .setPassword("1qazXSW@")
                .setDatabase(0)
                .setConnectionPoolSize(64)
                .setConnectionMinimumIdleSize(10);
        config.setLockWatchdogTimeout(30_000L);
        return Redisson.create(config);
    }


    @Bean
    public RedissonClient redissonClient2() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://120.27.236.84:6380")
                .setDatabase(0)
                .setConnectionPoolSize(64)
                .setConnectionMinimumIdleSize(10);
        config.setLockWatchdogTimeout(30_000L);
        return Redisson.create(config);
    }


    @Bean
    public RedissonClient redissonClient3() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://120.27.236.84:6381")
                .setDatabase(0)
                .setConnectionPoolSize(64)
                .setConnectionMinimumIdleSize(10);
        config.setLockWatchdogTimeout(30_000L);
        return Redisson.create(config);
    }

}
