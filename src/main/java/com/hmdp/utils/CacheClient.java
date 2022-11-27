package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {

    private final RedisTemplate<String, String> redisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 设置逻辑过期
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), time, unit);
    }


    public <R, I> R queryWithMutex(String keyPrefix,String lockKey, I id, Function<I, R> dbFallback,Class<R> type, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 先查询缓存
        String json = redisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            // 存在直接返回给前端
            return JSONUtil.toBean(json, type);
        }
        // 前面已经判断是否为空了，StrUtil.isNotBlank(shopJson)，这次不为null，说明是之前缓存的空值
        if (json != null) {
            return null;
        }
        // 获取互斥锁
       final String newLocKey = lockKey + id;
        R r;
        try {
            boolean lock = tryLock(newLocKey);
            //判断是否获取成功
            if (!lock) {
                // 获取失败 递归调用
                return queryWithMutex(keyPrefix,lockKey,id,dbFallback,type,time,unit);
            }
            // 获取成功,再次查询redis缓存
            String doubleJson = redisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(doubleJson)) {
                // 存在直接返回给前端
                return JSONUtil.toBean(doubleJson, type);
            }
            //redis不存在,查询数据库
            r = dbFallback.apply(id);
            // 判断商户是否存在
            if (r == null) {
                // 将空值存入redis，防止缓存穿透
                redisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 存在则存入redis
            redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), time, unit);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unlock(newLocKey);
        }
        return r;
    }


    /**
     * 防止缓存穿透
     *
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @param <R>
     * @param <I>
     * @return
     */
    public <R, I> R queryWithPassThrough(String keyPrefix, I id, Class<R> type, Function<I, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 先查询缓存
        String json = redisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            // 存在直接返回给前端
            return JSONUtil.toBean(json, type);
        }
        // 前面已经判断是否为空了，StrUtil.isNotBlank(shopJson)，这次不为null，说明是之前缓存的空值
        if (json != null) {
            return null;
        }
        // redis不存在
        R r = dbFallback.apply(id);
        // 判断商户是否存在
        if (r == null) {
            // 等于null，将空值存入redis，防止缓存穿透
            redisTemplate.opsForValue().set(key, "", time, unit);
            return null;
        }
        // 不等于null，存入redis
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 逻辑过期解决缓存击穿
     *
     * @param id
     * @return
     */
    public <R, I> R queryWithLogicalExpire(String keyPrefix, I id, Class<R> type, String lockKey, Function<I, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 先查询缓存
        String json = redisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            // 不存在
            return null;
        }
        // 命中 把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        // 店铺信息
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        // 过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否过期 （如果过期时间在当前时间之后说明还没有过期）
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期
            return r;
        }
        // 已过期 重建缓存
        final String newLockKey = lockKey + id;
        boolean lock = tryLock(newLockKey);
        // 判断是否获取锁成功
        if (lock) {
            // 双重缓存，如果该次查询redis存在，则无需重建缓存
            String doubleShopJson = redisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(doubleShopJson)) {
                // 存在直接返回给前端
                return JSONUtil.toBean(doubleShopJson, type);
            }
            // 获取成功 开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R apply = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, apply, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(newLockKey);
                }
            });
        }
        return r;
    }


    /**
     * 上锁
     *
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean aBoolean = redisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }

    /**
     * 删除锁
     *
     * @param key
     */
    private void unlock(String key) {
        redisTemplate.delete(key);

    }
}
