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

import static com.hmdp.utils.RedisConstants.BLOOM_FILTER_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;

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
    // 布隆过滤器
    private RBloomFilter<Long> shopBloomFilter;
    private TryLockUtil tryLockUtil;


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
    }

    // 缓存穿透：互斥锁解决
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
                        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                    }
                    return Result.ok(shop);
                } finally {
                    tryLockUtil.unLock(lockKey);
                }
            }
        }
        return Result.fail("系统繁忙，请稍后再试！");
    }

    // 缓存穿透：逻辑过期
    private Result queryWithExpire(Long id) {
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopInfo = stringRedisTemplate.opsForValue().get(shopKey);
        RedisData<Shop> shop = JSONUtil.toBean(shopInfo, new TypeReference<RedisData<Shop>>() {}, true);
        LocalDateTime expireTime = shop.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            log.info("商品缓存命中且逻辑未过期：{}", shopInfo);
            return Result.ok(shop.getData());
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
                    shop = JSONUtil.toBean(shopInfo, new TypeReference<RedisData<Shop>>() {}, true);
                    expireTime = shop.getExpireTime();
                    if (expireTime.isAfter(LocalDateTime.now())) {
                        log.info("商品缓存命中且逻辑未过期且已重试：{}", shopInfo);
                        return Result.ok(shop.getData());
                    }
                    Shop shopById = getById(id);
                    // 防止缓存击穿方案1：采用缓存空对象
                    if (Objects.isNull(shopById)) {
                        RedisData<Shop> nullData = new RedisData<>();
                        nullData.setData(null);
                        nullData.setExpireTime(LocalDateTime.now().plusMinutes(CACHE_NULL_TTL));
                        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(nullData),
                                CACHE_NULL_TTL, TimeUnit.MINUTES);
                    } else {
                        RedisData<Shop> shopRedisData = new RedisData<>();
                        shopRedisData.setData(shopById);
                        shopRedisData.setExpireTime(LocalDateTime.now().plusMinutes(20L));
                        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shopRedisData), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                    }
                    return Result.ok(shopById);
                } finally {
                    tryLockUtil.unLock(lockKey);
                }
            }
        }
        return Result.fail("系统繁忙，请稍后再试！");
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
