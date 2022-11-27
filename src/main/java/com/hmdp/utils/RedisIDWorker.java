package com.hmdp.utils;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIDWorker {

    @Resource
    private RedisTemplate<String,String> redisTemplate;
    private static final Long BEGIN_TIMESTAMP = 1640995200L;
    private static final Integer COUNT_BITS = 32;

    public long nextId(String keyPrefix){
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;
        // 获取当前日期
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 自增长
        Long count = redisTemplate.opsForValue().increment(RedisConstants.ICR_KEY + keyPrefix + ":" + date);
        return timeStamp << COUNT_BITS | count;
    }
}
