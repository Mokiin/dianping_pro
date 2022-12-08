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
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
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

    /**
     * 退出登录
     *
     * @return
     */
    @Override
    public Result logout() {
        redisTemplate.delete(REDIS_CACHE_USER);
        return Result.ok();
    }

    /**
     * 通过userID获取user对象
     *
     * @param userId
     * @return
     */
    @Override
    public Result getUserById(Long userId) {
        User user = baseMapper.selectById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    @Override
    public Result sign() {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接Key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        // 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 写入redis
        redisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接Key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        // 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 获取本月截止到今天的所有签到记录
        List<Long> result = redisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        int count = 0;
        // 循环遍历
        while (true){
            // 让这个属于与1做与运算，得到数字的最后一个bit位
            if ((num & 1) == 0){
                // 为0说明没有签到 结束循环
                break;
            }else {
                // 为1说明已经签到 计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最低位
            num >>>= 1;
        }
        return Result.ok(count);
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
