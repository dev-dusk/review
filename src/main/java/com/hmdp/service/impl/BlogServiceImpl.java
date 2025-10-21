package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.ScrollResult;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate template;
    @Resource
    private IUserService userService;


    /*@Override
    public Result likeBlog(Long id) {
        String userId = String.valueOf(UserHolder.getUser().getId());
        String blogKey = BLOG_LIKED_KEY + id;
        Boolean isExist = template.opsForSet().isMember(blogKey, userId);
        if (Boolean.FALSE.equals(isExist)) {
            boolean update = this.lambdaUpdate()
                    .setSql("liked = liked + 1")
                    .eq(Blog::getId, id)
                    .update();
            if (update) {
                template.opsForSet().add(blogKey, userId);
            }
        } else {
            boolean update = this.lambdaUpdate()
                    .setSql("liked = liked - 1")
                    .eq(Blog::getId, id)
                    .update();
            if (update) {
                template.opsForSet().remove(blogKey, userId);
            }
        }
        return Result.ok();
    }*/


    @Override
    public Result likeBlog(Long id) {
        String userId = String.valueOf(UserHolder.getUser().getId());
        String blogKey = BLOG_LIKED_KEY + id;
        Double score = template.opsForZSet().score(blogKey, userId);
        if (score == null) {
            boolean update = this.lambdaUpdate()
                    .setSql("liked = liked + 1")
                    .eq(Blog::getId, id)
                    .update();
            if (update) {
                template.opsForZSet().add(blogKey, userId, System.currentTimeMillis());
            }
        } else {
            boolean update = this.lambdaUpdate()
                    .setSql("liked = liked - 1")
                    .eq(Blog::getId, id)
                    .update();
            if (update) {
                template.opsForZSet().remove(blogKey, userId);
            }
        }
        return Result.ok();
    }


    @Override
    public Result getBlog(String id) {
        Blog blog = this.getById(id);
        String blogKey = BLOG_LIKED_KEY + id;
        String userId = String.valueOf(UserHolder.getUser().getId());
        if (blog != null) {
            Double score = template.opsForZSet().score(blogKey, userId);
            blog.setIsLike(Objects.isNull(score) ? Boolean.FALSE : Boolean.TRUE);
        }
        return Result.ok(blog);
    }


    /**
     * 查询前5名点赞用户
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String blogKey = BLOG_LIKED_KEY + id;
        Set<String> rangeLikes = template.opsForZSet().range(blogKey, 0, 4);
        if (CollectionUtils.isEmpty(rangeLikes)) {
            return Result.ok(Collections.emptyList());
        }
        String idStr = String.join(",", rangeLikes);
        List<User> userList = userService.lambdaQuery()
                .in(User::getId, rangeLikes)
                .last("order by field (id, " + idStr + ")")
                .list();
        return Result.ok(userList);
    }


    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        String userId = String.valueOf(UserHolder.getUser().getId());
        String feedKey = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = template.opsForZSet()
                .reverseRangeByScoreWithScores(feedKey, 0, max, offset, 2);
        if (CollectionUtils.isEmpty(typedTuples)) {
            log.info("暂无关注发布文章");
            return Result.ok();
        }
        // 解析数据
        List<String> ids = new ArrayList<>(typedTuples.size());
        AtomicLong minTime = new AtomicLong(0L);
        AtomicInteger offsetCount = new AtomicInteger(1);
        typedTuples.forEach(tuple -> {
            ids.add(tuple.getValue());
            long score = Objects.requireNonNull(tuple.getScore()).longValue();
            if (minTime.get() == score) {
                offsetCount.getAndIncrement();
            } else {
                minTime.set(score);
                offsetCount.set(1);
            }
        });
        offsetCount.set(minTime.get() == max ? offsetCount.get() : offsetCount.addAndGet(offset));
        String idStr = String.join(",", ids);
        List<Blog> blogList = this.lambdaQuery()
                .in(Blog::getId, ids)
                .last("order by FIELD (id," + idStr + ")")
                .list();
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogList);
        scrollResult.setMinTime(minTime.get());
        scrollResult.setOffset(offsetCount.get());
        return Result.ok(scrollResult);
    }




}
