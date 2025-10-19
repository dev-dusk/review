package com.hmdp.lock;

import cn.hutool.core.lang.UUID;
import com.google.common.base.Objects;
import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

@AllArgsConstructor
public class UnMisDelLock implements ILock{

    private static final String LOCK_KEY = "lock:shop:";
    private String lockName;
    private StringRedisTemplate stringRedisTemplate;
    private static final String JVM_ID = UUID.randomUUID().toString();


    @Override
    public boolean tryLock(long ttl) {
        String lockKey = LOCK_KEY + lockName;
        long threadId = Thread.currentThread().getId();
        String value = JVM_ID + "$" + threadId;
        Boolean lockSuccess = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, value,
                ttl, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(lockSuccess);
    }

    @Override
    public void unLock() {
        String lockKey = LOCK_KEY + lockName;
        long threadId = Thread.currentThread().getId();
        String value = JVM_ID + "$" + threadId;
        String redisValue = stringRedisTemplate.opsForValue().get(lockKey);
        if (Objects.equal(value, redisValue)) {
            stringRedisTemplate.delete(lockKey);
        }
    }

}
