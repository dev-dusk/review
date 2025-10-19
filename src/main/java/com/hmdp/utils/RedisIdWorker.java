package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 1bit符号位，为0标识正数
 * 31bit位，标识时间戳
 * 32bit位，标识序列号
 */
@Slf4j
@Component
public class RedisIdWorker {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 开始时间戳，秒级
    private static final long BEGIN_TIMESTAMP = 1760541448L;
    // 序列号的位数
    private static final int COUNT_BITS = 32;
    // 全局key
    private static final String INCREMENT_KEY = "icr:id:";

    public long nextId() {
        // 获取相对秒
        long epochSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
//        log.info("epochSecond: {}", epochSecond);
        long timestamp = epochSecond - BEGIN_TIMESTAMP;

        String postDate = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        long increment = stringRedisTemplate.opsForValue().increment(INCREMENT_KEY + postDate);
        return timestamp << COUNT_BITS | increment;

    }






}
