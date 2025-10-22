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
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.TryLockUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
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
//@RequiredArgsConstructor
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource(name = "redissonClient1")
    private  RedissonClient redissonClient1;
    @Resource
    private  TryLockUtil tryLockUtil;
    // 布隆过滤器
    private RBloomFilter<Long> shopBloomFilter;

    /**
     * 初始化布隆过滤器
     */
    @PostConstruct
    public void initRBloomFilter() {
        CompletableFuture.runAsync(() -> {
            shopBloomFilter = redissonClient1.getBloomFilter(BLOOM_FILTER_SHOP_KEY);
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
        String shopKey = CACHE_SHOP_KEY + id;
        String shopInfo = stringRedisTemplate.opsForValue().get(shopKey);
        if (StringUtils.hasText(shopInfo)) {
            log.info("商品缓存命中：{}", shopInfo);
            Shop shop = JSONUtil.toBean(shopInfo, Shop.class);
            return Result.ok(shop);
        }
        // 获取锁并更新缓存
        log.warn("未命中商品{}缓存", id);
        String lockKey = LOCK_SHOP_KEY + id;
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
                        stringRedisTemplate.opsForValue().set(shopKey, CACHE_NULL_KEY,
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
        String shopKey = CACHE_SHOP_KEY + id;
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
        String lockKey = LOCK_SHOP_KEY + id;
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
        String shopKey = CACHE_SHOP_KEY + shop.getId();
        stringRedisTemplate.delete(shopKey);
        return Result.ok("更新商品成功");
    }


    @Override
    public Result deleteShop(String id) {
        boolean remove = this.removeById(id);
        if (!remove) {
            return Result.fail("删除商品数据失败");
        }
        String shopKey = CACHE_SHOP_KEY + id;
        stringRedisTemplate.delete(shopKey);
        return Result.ok("删除商品成功");
    }


    @Override
    public Result queryShopByType(Integer typeId, Integer current, String x, String y) {
        // 根据类型分页查询
//        Page<Shop> page = query()
//                .eq("type_id", typeId)
//                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
//        if (CollectionUtils.isEmpty(page.getRecords())) {
//            log.info("查询店铺数据为空");
//            return Result.ok(Collections.emptyList());
//        }
        int pageStart = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int pageEnd = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String geoKey = SHOP_GEO_KEY + typeId;
        // 使用 radius 方法替代 search 方法，避免 StackOverflowError
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoSearch = stringRedisTemplate.opsForGeo().radius(geoKey,
                // 使用 Circle 对象定义圆形区域：中心点 + 半径
                new org.springframework.data.geo.Circle(
                        new Point(Double.parseDouble(x), Double.parseDouble(y)),
                        new Distance(5000, RedisGeoCommands.DistanceUnit.METERS)
                ),
                // 包含距离和坐标信息，并限制数量
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance()
                        .sortAscending()
                        .limit(pageEnd));
        if (Objects.isNull(geoSearch)) {
            log.info("查询店铺数据为空");
            return Result.ok(Collections.emptyList());
        }
        Map<String, Double> distanceMap = new HashMap<>();
        // 跳过pageStart前面的数据
        geoSearch.getContent().stream().skip(pageStart).forEach(result -> {
            distanceMap.put(result.getContent().getName(), result.getDistance().getValue());
        });
        if (distanceMap.isEmpty()) {
            log.info("查询店铺数据为空");
            return Result.ok(Collections.emptyList());
        }
        List<Shop> shopList = this.lambdaQuery()
                .in(Shop::getId, distanceMap.keySet())
                .last("order by FIELD(id," + String.join(",", distanceMap.keySet()) + ")")
                .list();
        shopList.forEach(shopItem -> shopItem.setDistance(distanceMap.get(String.valueOf(shopItem.getId()))));
        // 返回数据
        return Result.ok(shopList);
    }



    @PostConstruct
    public void initGeo() {
        List<Shop> shopList = list();
        if (CollectionUtils.isEmpty(shopList)) {
            return;
        }
        Map<Long, List<Shop>> typeMap = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        typeMap.forEach((key, value) -> {
            String geoKey = SHOP_GEO_KEY + key;
            List<RedisGeoCommands.GeoLocation<String>> geoLocationList = value.stream().map(shopItem -> {
                Point point = new Point(shopItem.getX(), shopItem.getY());
                return new RedisGeoCommands.GeoLocation<>(String.valueOf(shopItem.getId()), point);
            }).collect(Collectors.toList());
            stringRedisTemplate.opsForGeo().add(geoKey, geoLocationList);
        });
        log.info("初始化GEO商铺位置完成");
    }

}
