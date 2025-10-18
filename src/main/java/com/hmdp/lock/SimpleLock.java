package com.hmdp.lock;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class SimpleLock implements ILock{

    private static final String LOCK_KEY = "lock:shop:";
    private String lockName;
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public boolean tryLock(long ttl) {
        String lockKey = LOCK_KEY + lockName;
        long threadId = Thread.currentThread().getId();
        Boolean lockSuccess = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, String.valueOf(threadId),
                ttl, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(lockSuccess);
    }

    @Override
    public void unLock() {
        String lockKey = LOCK_KEY + lockName;
        stringRedisTemplate.delete(lockKey);
    }

}
