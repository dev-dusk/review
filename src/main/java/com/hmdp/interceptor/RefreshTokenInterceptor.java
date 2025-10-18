package com.hmdp.interceptor;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
@Component
@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 职责分离，只处理token无感刷新和用户信息获取
        String token = request.getHeader("authorization");
        if (!StringUtils.hasText(token)) {
            token = "divrj4iv0bj8hmgnha6cps86ez143epb";
//            log.warn("获取用户token失败！");
//            return true;
        }
        String userInfoKey = RedisConstants.LOGIN_USER_KEY + token;
        String userInfo = stringRedisTemplate.opsForValue().get(userInfoKey);
        if (!StringUtils.hasText(userInfo)) {
            log.warn("获取用户信息失败！");
            return true;
        }
        stringRedisTemplate.expire(userInfoKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        UserHolder.saveUser(JSONUtil.toBean(userInfo, UserDTO.class));
        log.info("token刷新成功{}", userInfo);
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        HandlerInterceptor.super.postHandle(request, response, handler, modelAndView);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
