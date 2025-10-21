package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;

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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private FollowMapper followMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private UserMapper userMapper;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String followKey = FOLLOW_KEY + userId;
        if (isFollow) {
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            follow.setCreateTime(LocalDateTime.now());
            boolean save = this.save(follow);
            if (save) {
                stringRedisTemplate.opsForSet().add(followKey, String.valueOf(followUserId));
            }
            return Result.ok("关注成功");
        } else {
            boolean remove = remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, followUserId));
            if (remove) {
                stringRedisTemplate.opsForSet().remove(followKey, String.valueOf(followUserId));
            }
            return Result.ok("取关成功");
        }
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = this.lambdaQuery()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, followUserId)
                .count();
        return Result.ok(count > 0);
    }


    @Override
    public Result followCommons(Long followUserId) {
        String userId = String.valueOf(UserHolder.getUser().getId());
        String currentUserKey = FOLLOW_KEY + userId;
        String followUserKey = FOLLOW_KEY + followUserId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(currentUserKey, followUserKey);
        if (CollectionUtils.isEmpty(intersect)) {
            log.info("{}和{}暂无共同关注", userId, followUserId);
            return Result.ok(Collections.emptyList());
        }
        List<UserDTO> commonUser = userMapper.selectBatchIds(intersect).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(commonUser);
    }




}
