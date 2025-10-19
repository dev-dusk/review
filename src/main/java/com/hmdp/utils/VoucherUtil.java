package com.hmdp.utils;

import com.hmdp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Slf4j
public class VoucherUtil {

    // 声明异步阻塞队列
    public final BlockingQueue<VoucherOrder> ORDER_TASKS = new ArrayBlockingQueue<>(1024 * 1024);

    // 声明异步线程池
    public final ExecutorService ORDER_EXECUTOR = Executors.newSingleThreadExecutor();


}
