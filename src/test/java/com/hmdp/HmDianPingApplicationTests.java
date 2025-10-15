package com.hmdp;

import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Test
    void testId() {
        for (int i = 0; i < 100; i++) {
            System.out.println(redisIdWorker.nextId());
        }

    }
}