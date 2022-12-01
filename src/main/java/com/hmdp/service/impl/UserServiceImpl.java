package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
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
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Resource
    private IUserService userService;

    private static String REDIS_CACHE_USER = "";

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合 返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 符合 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 保存验证码到redis
        redisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // session.setAttribute(SystemConstants.SESSION_CODE, code);
        // 发送验证码
        log.debug("验证码发送成功{}", code);
        return Result.ok();
    }

    @Override
    public Result passLogin(LoginFormDTO loginForm, HttpSession session) {
        // 前端返回的手机号
        String phone = loginForm.getPhone();
        // 判断手机号格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 前端返回的验证码
        String code = loginForm.getCode();
        // 从redis获取验证码
        String cacheCode = redisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        // 判断redis中是否存在验证码及验证码是否正确
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }
        // 通过手机号向数据库查询
        QueryWrapper<User> wrapper = new QueryWrapper<User>().eq(SystemConstants.PHONE, phone);
        // 数据库查询到的用户
         User user = baseMapper.selectOne(wrapper);
        // 如果数据库没有该用户就注册
        if (user == null) {
            user = createUserWithPhone(phone, loginForm.getPassword());
        }
        // 随机UUID作为redis的key
        String key = UUID.randomUUID().toString(true);
        // 将user对象主要属性提取放入到userDTO
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 将userDTO转换为map
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        // 存入redis
        String tokenKey = RedisConstants.LOGIN_USER_KEY + key;
        REDIS_CACHE_USER = tokenKey;
        redisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 设置key的有效期
        redisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        // 把token返回到前端
        return Result.ok(key);
    }

    @Override
    public Result logout() {
        redisTemplate.delete(REDIS_CACHE_USER);
        return Result.ok();
    }

    private User createUserWithPhone(String phone, String password) {
        User user = new User();
        // 未注册过的手机号
        user.setPhone(phone);
        // 随机用户名
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        user.setCreateTime(LocalDateTime.now());
        if (!"".equals(password)) {
            user.setPassword(password);
        }
        baseMapper.insert(user);
        return user;
    }
}
