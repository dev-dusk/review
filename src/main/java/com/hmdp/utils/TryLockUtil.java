package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class TryLockUtil {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    public static String LOCK_VALUE = "1";

    public boolean tryLock(String lockKey) {
        Boolean ifAbsent = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, LOCK_VALUE, 10, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(ifAbsent);
    }

    public void unLock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }


}
