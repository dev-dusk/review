package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.PasswordEncoder;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
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
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final String DEFAULT_PASSWORD = "123456";
    private final String DEFAULT_NAME = "dusk";
    private final StringRedisTemplate stringRedisTemplate;
    private final UserMapper userMapper;
    // 13912345678

    @Override
    public Result sendCode(String phone, HttpSession session) {
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        if (phoneInvalid) {
            log.warn("手机号{}格式不正确!!!", phone);
            return Result.fail("手机号格式不正确！");
        }
        String code = RandomUtil.randomString(6);
        System.err.println("=============================================");
        System.err.println(code);
        System.err.println("=============================================");
        String redisKey = String.format(RedisConstants.LOGIN_CODE_KEY, phone);
        stringRedisTemplate.opsForValue().set(redisKey, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        session.setAttribute("code", code);
        log.info("发送验证码成功：{}", code);
        return Result.ok(code);
    }


    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        if (Objects.isNull(loginForm) || RegexUtils.isPhoneInvalid(loginForm.getPhone()) || RegexUtils.isCodeInvalid(loginForm.getCode())) {
            log.warn("登录请求参数非法：{}", loginForm);
            return Result.fail("登录请求参数非法");
        }
        String codeKey = String.format(RedisConstants.LOGIN_CODE_KEY, loginForm.getPhone());
        String code = stringRedisTemplate.opsForValue().get(codeKey);
        if (!Objects.equals(code, loginForm.getCode())) {
            log.warn("验证码不一致：{} -> {}", code, loginForm.getCode());
            return Result.fail("验证码不一致或过期");
        }
        stringRedisTemplate.delete(codeKey);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, loginForm.getPhone()));
        if (Objects.isNull(user)) {
            log.info("用户登陆身份不存在，新建用户信息: {}", loginForm);
            user = new User();
            user.setId(RandomUtil.randomLong());
            user.setPhone(loginForm.getPhone());
            user.setPassword(PasswordEncoder.encode(DEFAULT_PASSWORD));
            user.setNickName(DEFAULT_NAME);
            user.setCreateTime(LocalDateTime.now());
            user.setUpdateTime(LocalDateTime.now());
            userMapper.insert(user);
            return Result.ok();
        } else {
            if (PasswordEncoder.matches(user.getPassword(), loginForm.getPassword())) {
                log.warn("密码不一致：{} -> {}", code, loginForm.getCode());
                return Result.fail("密码不一致");
            }
        }
        String token = RandomUtil.randomString(32);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String loginKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForValue().set(loginKey, JSONUtil.toJsonStr(userDTO), RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }


    @Override
    public Result me() {
        UserDTO user = UserHolder.getUser();
        log.info("获取用户：{}", user);
        return Result.ok(user);
    }









}
