package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.VoucherUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final VoucherOrderMapper voucherOrderMapper;
    private final ISeckillVoucherService seckillVoucherService;
    private final RedisIdWorker redisIdWorker;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private static final String LOCK_KEY = "lock:shop:";
    private static final String PREHEAT_KEY = "seckill:stock:";
    private final VoucherUtil voucherUtil;
    private static DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    @PostConstruct
    public void preheatSeckill() {
        List<SeckillVoucher> seckillVoucherList = seckillVoucherService.list();
        seckillVoucherList.forEach( seckill -> {
            if (seckill.getBeginTime().isBefore(LocalDateTime.now()) && seckill.getEndTime().isAfter(LocalDateTime.now())) {
                String preKey = PREHEAT_KEY + seckill.getVoucherId();
                long seconds = Duration.between(LocalDateTime.now(), seckill.getEndTime()).getSeconds();
                stringRedisTemplate.opsForValue().set(preKey, seckill.getStock().toString(), seconds, TimeUnit.SECONDS);
                log.info("优惠劵{}预热成功！", seckill.getVoucherId());
            }
        });
        // 初始化异步处理器
        OrderHandler orderHandler = new OrderHandler();
        voucherUtil.ORDER_EXECUTOR.submit(orderHandler);
    }




    /**
     * 秒杀优惠劵下单
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // redis单线程执行，串行执行lua命令，所以不会出现同时执行多卖情况
        Long execute = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(), UserHolder.getUser().getId().toString(), String.valueOf(redisIdWorker.nextId()));
        if (!Objects.equals(execute, 0L)) {
            return Result.fail(Objects.equals(execute, 1L) ? "优惠劵已空" : "已购买过");
        }
        // 异步生产订单
        VoucherOrder voucherOrder = VoucherOrder.builder()
                .id(redisIdWorker.nextId())
                .userId(UserHolder.getUser().getId())
                .voucherId(voucherId)
                .payType(1)
                .status(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        voucherUtil.ORDER_TASKS.add(voucherOrder);
        return Result.ok("订单创建成功");
    }


    /**
     * 订单生成的逻辑
     * @param voucherId
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result doVoucherOrder(Long voucherId) {
        // 1.一人一单逻辑
        Integer orderCount = this.lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .eq(VoucherOrder::getUserId, UserHolder.getUser().getId())
                .count();
        if (orderCount > 0) {
            log.warn("用户{}已抢购过该{}优惠券", UserHolder.getUser().getId(), voucherId);
            return Result.fail("用户已抢购过该优惠劵");
        }
        // 2.扣减库存
        boolean success = seckillVoucherService.lambdaUpdate()
                .setSql("stock = stock - 1")
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0)
                .update();
        if (!success) {
            log.warn("{}秒杀劵已抢购完", voucherId);
            return Result.fail("秒杀劵已抢购完");
        }
        // 3.创建订单
        long orderId = saveVoucherOrder(voucherId);
        return Result.ok("订单创建成功" + orderId);
    }

    /**
     * 保存优惠劵购买订单
     * @param voucherId
     * @return
     */
    private long saveVoucherOrder(Long voucherId) {
        long orderId = redisIdWorker.nextId();
        VoucherOrder voucherOrder = VoucherOrder.builder()
                .id(orderId)
                .userId(UserHolder.getUser().getId())
                .voucherId(voucherId)
                .payType(1)
                .status(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        this.save(voucherOrder);
        return orderId;
    }


    /**
     * 恢复优惠劵的状态
     * @return
     */
    @Override
    public Result recover() {
        voucherOrderMapper.recoverOrder();
        voucherOrderMapper.recoverStock();
        return Result.ok();
    }



    private class OrderHandler implements Runnable{
        @Override
        public void run() {
            while (true) {
                try {
                    VoucherOrder take = voucherUtil.ORDER_TASKS.take();
                    Long voucherId = take.getVoucherId();
                    // todo
//                    Integer orderCount = voucherOrderService.lambdaQuery()
//                            .eq(VoucherOrder::getVoucherId, voucherId)
//                            .eq(VoucherOrder::getUserId, UserHolder.getUser().getId())
//                            .count();
//                    if (orderCount > 0) {
//                        log.warn("用户{}已抢购过该{}优惠券", UserHolder.getUser().getId(), voucherId);
//                        return;
//                    }
                    //扣减库存
                    boolean success = seckillVoucherService.lambdaUpdate()
                            .setSql("stock = stock - 1")
                            .eq(SeckillVoucher::getVoucherId, voucherId)
                            .gt(SeckillVoucher::getStock, 0)
                            .update();
                    if (!success) {
                        log.warn("{}秒杀劵已抢购完", voucherId);
                        return;
                    }
                    save(take);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }













}
