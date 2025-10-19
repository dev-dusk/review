package com.hmdp.lock;

import cn.hutool.core.lang.UUID;
import lombok.AllArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@AllArgsConstructor
public class AtomicLock implements ILock{

    private static final String LOCK_KEY = "lock:shop:";
    private String lockName;
    private StringRedisTemplate stringRedisTemplate;
    private static final String JVM_ID = UUID.randomUUID().toString();
    private static final DefaultRedisScript<Long> UN_LOCK_SCRIPT;
    static {
        UN_LOCK_SCRIPT = new DefaultRedisScript<>();
        UN_LOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UN_LOCK_SCRIPT.setResultType(Long.class);
    }


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
        stringRedisTemplate.execute(UN_LOCK_SCRIPT,
                Collections.singletonList(LOCK_KEY + lockName),
                JVM_ID + "$" + Thread.currentThread().getId());
    }

}
