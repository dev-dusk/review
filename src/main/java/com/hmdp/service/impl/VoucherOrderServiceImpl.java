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
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final VoucherOrderMapper voucherOrderMapper;
    private final ISeckillVoucherService seckillVoucherService;
    private final RedisIdWorker redisIdWorker;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient1;
    private final RedissonClient redissonClient2;
    private final RedissonClient redissonClient3;
    private static final String LOCK_KEY = "lock:shop:";

    public VoucherOrderServiceImpl(
            VoucherOrderMapper voucherOrderMapper,
            ISeckillVoucherService seckillVoucherService,
            RedisIdWorker redisIdWorker,
            StringRedisTemplate stringRedisTemplate,
            @Qualifier("redissonClient1") RedissonClient redissonClient1,
            @Qualifier("redissonClient2") RedissonClient redissonClient2,
            @Qualifier("redissonClient3") RedissonClient redissonClient3) {
        this.voucherOrderMapper = voucherOrderMapper;
        this.seckillVoucherService = seckillVoucherService;
        this.redisIdWorker = redisIdWorker;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient1 = redissonClient1;
        this.redissonClient2 = redissonClient2;
        this.redissonClient3 = redissonClient3;
    }



    /**
     * 秒杀优惠劵下单
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.先判断是否生效
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (Objects.isNull(seckillVoucher)) {
            log.warn("当前{}优惠劵不存在", voucherId);
            return Result.fail("当前优惠劵不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean isStart = now.isAfter(seckillVoucher.getBeginTime()) && now.isBefore(seckillVoucher.getEndTime());
        if (!isStart) {
            log.warn("{}秒杀活动未开启或已结束", voucherId);
            return Result.fail("秒杀活动未开启或已结束");
        }
        if (seckillVoucher.getStock() < 1) {
            log.warn("{}秒杀劵库存不足", voucherId);
            return Result.fail("秒杀劵库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        String redisKey = LOCK_KEY + userId.toString();
//        ILock lock = new AtomicLock(userId.toString(), stringRedisTemplate);
//        boolean tryLock = lock.tryLock(10_000);
//        RLock lock = redissonClient1.getLock(redisKey);
//        RLock lock = redissonClient1.getLock(redisKey);
        RLock lock1 = redissonClient1.getLock(redisKey);
        RLock lock2 = redissonClient2.getLock(redisKey);
        RLock lock3 = redissonClient3.getLock(redisKey);
        RLock rLock = redissonClient1.getRedLock(lock1, lock2, lock3);
        try {
            // 参数说明：
            // 1. waitTime: 10 - 等待获取锁的最长时间（10秒）
            // 2. leaseTime: 30 - 锁的自动释放时间（30秒）
            // 3. TimeUnit: 时间单位
            boolean tryLock = rLock.tryLock(10, -1, TimeUnit.SECONDS);
            if (!tryLock) {
                return Result.fail("不能重复下单");
            }
            IVoucherOrderService currentProxy = (IVoucherOrderService) AopContext.currentProxy();
            return currentProxy.doVoucherOrder(voucherId);
        } catch (InterruptedException e) {
            log.error("获取锁被中断");
            throw new RuntimeException(e);
        } finally {
            rLock.unlock();
        }
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
}
