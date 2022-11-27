package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {

    private final RedisTemplate<String, String> redisTemplate;

    public LoginInterceptor(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 从请求头获取token
        String token = request.getHeader(SystemConstants.TOKEN);
        // 判断token存不存在
        if (StrUtil.isBlank(token)) {
            // 不存在 拦截
            response.setStatus(401);
            return false;
        }
        String key = RedisConstants.LOGIN_USER_KEY + token;
        // 从redis获取用户
        Map<Object, Object> userMap = redisTemplate.opsForHash().entries(key);
        // Object user = request.getSession().getAttribute(SystemConstants.SESSION_USER);
        // 判断用户是否存在
        if (userMap.isEmpty()) {
            // 不存在 拦截
            response.setStatus(401);
            return false;
        }
        // map转为UserDto
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 保存信息到threadLocal
        UserHolder.saveUser(userDTO);
        // 刷新token有效期
        redisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
