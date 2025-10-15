package com.hmdp.service.impl;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.TryLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final TryLockUtil tryLockUtil;
    // 布隆过滤器
    private RBloomFilter<Long> shopBloomFilter;


    /**
     * 初始化布隆过滤器
     */
    @PostConstruct
    public void initRBloomFilter() {
        CompletableFuture.runAsync(() -> {
            shopBloomFilter = redissonClient.getBloomFilter(BLOOM_FILTER_SHOP_KEY);
            int totalCount = this.count();
            shopBloomFilter.tryInit(totalCount, 0.01);

            int pageSize = 5000;
            int loadedCount = 0;
            // 向上取整
            int totalPages = (totalCount + pageSize - 1) / pageSize;
            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                List<Long> shopIds = this.lambdaQuery()
                        .select(Shop::getId)
                        .page(Page.of(pageNum, pageSize))
                        .getRecords()
                        .stream()
                        .map(Shop::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                if (CollectionUtils.isEmpty(shopIds)) {
                    log.info("商品布隆过滤缓存完成或暂无数据！");
                    break;
                }
                // 将商品ID存储在布隆过滤器里面
                shopIds.forEach(shopBloomFilter::add);
                loadedCount += shopIds.size();
                log.info("布隆过滤器总进度：{}/{}", loadedCount, totalCount);
                // 最后一页
                if (shopIds.size() < pageSize) {
                    break;
                }
            }
            log.info("布隆过滤器初始化完成，共加载 {} 个商铺ID", loadedCount);
        });
    }


    @Override
    public Result queryShopById(Long id) {
        if (Objects.isNull(id)) {
            return Result.fail("商品id为空！");
        }
        // 防止缓存击穿方案2：布隆过滤器
        if (!shopBloomFilter.contains(id)) {
            log.warn("布隆过滤器拦截，商品ID:{}不存在", id);
            return Result.fail("商品不存在");
        }
        return queryWithMutex(id);
//        return queryWithExpire(id);
    }

    // 缓存击穿：互斥锁解决
    private Result queryWithMutex(Long id) {
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopInfo = stringRedisTemplate.opsForValue().get(shopKey);
        if (StringUtils.hasText(shopInfo)) {
            log.info("商品缓存命中：{}", shopInfo);
            Shop shop = JSONUtil.toBean(shopInfo, Shop.class);
            return Result.ok(shop);
        }
        // 获取锁并更新缓存
        log.warn("未命中商品{}缓存", id);
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        int retries = 3;
        while (retries-- > 0) {
            if (tryLockUtil.tryLock(lockKey)) {
                try {
                    // 双检机制
                    shopInfo = stringRedisTemplate.opsForValue().get(shopKey);
                    if (StringUtils.hasText(shopInfo)) {
                        log.info("商品重试缓存命中：{}", shopInfo);
                        Shop shop = JSONUtil.toBean(shopInfo, Shop.class);
                        return Result.ok(shop);
                    }
                    Shop shop = getById(id);
                    // 防止缓存击穿方案1：采用缓存空对象
                    if (Objects.isNull(shop)) {
                        stringRedisTemplate.opsForValue().set(shopKey, RedisConstants.CACHE_NULL_KEY,
                                CACHE_NULL_TTL, TimeUnit.MINUTES);
                    } else {
                        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
                    }
                    return Result.ok(shop);
                } finally {
                    tryLockUtil.unLock(lockKey);
                }
            }
        }
        return Result.fail("系统繁忙，请稍后再试！");
    }

    // 缓存击穿：逻辑过期
    private Result queryWithExpire(Long id) {
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopInfo = stringRedisTemplate.opsForValue().get(shopKey);
        // 1.如果缓存未存在，查询数据库并设置逻辑过期时间
        if (!StringUtils.hasText(shopInfo)) {
            Shop shopById = getById(id);
            if (Objects.isNull(shopById)) {
                RedisData<Shop> nullData = new RedisData<>();
                nullData.setData(null);
                nullData.setExpireTime(LocalDateTime.now().plusMinutes(CACHE_NULL_TTL));
                stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(nullData));
                return Result.fail("商品不存在！");
            }
            RedisData<Shop> shopRedisData = new RedisData<>();
            shopRedisData.setData(shopById);
            shopRedisData.setExpireTime(LocalDateTime.now().plusMinutes(CACHE_SHOP_TTL));
            stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shopRedisData));
            return Result.ok(shopById);
        }

        //2.命中缓存，并且未逻辑过期
        RedisData<Shop> shopData = JSONUtil.toBean(shopInfo, new TypeReference<RedisData<Shop>>() {
        }, true);
        if (shopData.getExpireTime().isAfter(LocalDateTime.now())) {
            log.info("缓存未逻辑过期:{}", shopData);
            return Result.ok(shopData.getData());
        }

        // 3.逻辑已过期，先返回旧数据，再去异步更新
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        if (tryLockUtil.tryLock(lockKey)) {
            CompletableFuture.runAsync(() -> {
                // 双检机制
                try {
                    String newShopInfo = stringRedisTemplate.opsForValue().get(shopKey);
                    RedisData<Shop> newShop = JSONUtil.toBean(newShopInfo, new TypeReference<RedisData<Shop>>() {}, true);
                    LocalDateTime expireTime = newShop.getExpireTime();
                    if (expireTime.isAfter(LocalDateTime.now())) {
                        log.info("已由其他线程更新缓存：{}", shopInfo);
                        return;
                    }
                    Shop newShopData = getById(id);
                    RedisData<Shop> newShopRedisData = new RedisData<>();
                    newShopRedisData.setData(newShopData);
                    newShopRedisData.setExpireTime(LocalDateTime.now().plusMinutes(CACHE_SHOP_TTL));
                    stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(newShopRedisData));
                    log.info("异步更新缓存成功，商品:{}", newShopData);
                } finally {
                    tryLockUtil.unLock(lockKey);
                }
            });
        }

        log.info("返回旧数据，商品：{}", shopData);
        return Result.ok(shopData.getData());
    }


    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        // 方案：先操作数据库，再去删除缓存
        boolean update = this.updateById(shop);
        if (!update) {
            return Result.fail("更新商品数据失败！");
        }
        String shopKey = RedisConstants.CACHE_SHOP_KEY + shop.getId();
        stringRedisTemplate.delete(shopKey);
        return Result.ok("更新商品成功");
    }


    @Override
    public Result deleteShop(String id) {
        boolean remove = this.removeById(id);
        if (!remove) {
            return Result.fail("删除商品数据失败");
        }
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        stringRedisTemplate.delete(shopKey);
        return Result.ok("删除商品成功");
    }
}
