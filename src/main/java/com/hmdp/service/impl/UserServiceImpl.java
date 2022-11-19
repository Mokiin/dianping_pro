package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;

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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合 返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 符合 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 保存验证码到session
        session.setAttribute(SystemConstants.SESSION_CODE, code);
        // 发送验证码
        log.debug("验证码发送成功{}", code);
        return Result.ok();
    }

    @Override
    public Result passLogin(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        String code = loginForm.getCode();
        Object cacheCode = session.getAttribute(SystemConstants.SESSION_CODE);
        // 判断session是否存在验证码
        if (cacheCode == null || !cacheCode.toString().equals(code)){
            return Result.fail("验证码错误");
        }
        // 通过手机号向数据库查询
        QueryWrapper<User> wrapper = new QueryWrapper<User>().eq("phone", phone);
        User user = baseMapper.selectOne(wrapper);
        // 如果数据库没有该用户就注册加入数据库
        if (user == null){
            user = createUserWithPhone(phone,loginForm.getPassword());
        }
        // 把用户存入到session
        session.setAttribute(SystemConstants.SESSION_USER,user);
        return Result.ok();
    }

    private User createUserWithPhone(String phone,String password) {
        User user = new User();
        // 未注册过的手机号
        user.setPhone(phone);
        // 随机用户名
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        user.setCreateTime(LocalDateTime.now());
        user.setPassword(password);
        return user;
    }
}
