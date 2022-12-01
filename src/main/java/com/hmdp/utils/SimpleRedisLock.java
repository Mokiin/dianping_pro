package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private RedisTemplate<String, String> redisTemplate;
    private String name;

    public SimpleRedisLock(RedisTemplate<String, String> redisTemplate, String name) {
        this.redisTemplate = redisTemplate;
        this.name = name;
    }

    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unLock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean aBoolean = redisTemplate.opsForValue().setIfAbsent(RedisConstants.KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(aBoolean);
    }

    /**
     * LUA脚本实现解锁 保证原子性
     */
    @Override
    public void unlock() {
        redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(RedisConstants.KEY_PREFIX + name), ID_PREFIX + Thread.currentThread().getId());
    }

//    @Override
//    public void unlock() {
//        // 获取线程标识，该标识也保存在redis中
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        // 获取保存在redis中键为”RedisConstants.KEY_PREFIX + name“的值
//        String id = redisTemplate.opsForValue().get(RedisConstants.KEY_PREFIX + name);
//        // 判断
//        if (threadId.equals(id)){
//            redisTemplate.delete(RedisConstants.KEY_PREFIX + name);
//        }
//    }
}
