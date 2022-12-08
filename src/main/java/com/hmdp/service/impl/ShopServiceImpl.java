package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private RedisTemplate<String, String> redisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryShopById(Long id) {
        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

//    public Shop queryWithMutex(Long id) {
//        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        // 先查询缓存
//        String shopJson = redisTemplate.opsForValue().get(key);
//        if (StrUtil.isNotBlank(shopJson)) {
//            log.debug("走Redis缓存");
//            // 存在直接返回给前端
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        // 前面已经判断是否为空了，StrUtil.isNotBlank(shopJson)，这次不为null，说明是之前缓存的空值
//        if (shopJson != null) {
//            return null;
//        }
//        // 获取互斥锁
//        String lockKEY = RedisConstants.LOCK_SHOP_KEY + id;
//        Shop shop;
//        try {
//            boolean lock = tryLock(lockKEY);
//            //判断是否获取成功
//            if (!lock) {
//                // 获取失败 递归调用
//                return queryWithMutex(id);
//            }
//            // 获取成功,再次查询redis缓存
//            String doubleShopJson = redisTemplate.opsForValue().get(key);
//            if (StrUtil.isNotBlank(doubleShopJson)) {
//                // 存在直接返回给前端
//                return JSONUtil.toBean(doubleShopJson, Shop.class);
//            }
//            //redis不存在,查询数据库
//            shop = baseMapper.selectById(id);
//            // 判断商户是否存在
//            if (shop == null) {
//                // 将空值存入redis，防止缓存穿透
//                redisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            // 存在则存入redis
//            redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        } finally {
//            // 释放互斥锁
//            unlock(lockKEY);
//        }
//        return shop;
//    }

//    public Shop queryWithPassThrough(Long id) {
//        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        // 先查询缓存
//        String shopJson = redisTemplate.opsForValue().get(key);
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 存在直接返回给前端
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        // 前面已经判断是否为空了，StrUtil.isNotBlank(shopJson)，这次不为null，说明是之前缓存的空值
//        if (shopJson != null) {
//            return null;
//        }
//        // redis不存在
//        Shop shop = baseMapper.selectById(id);
//        // 判断商户是否存在
//        if (shop == null) {
//            // 将空值存入redis，防止缓存穿透
//            redisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        // 存入redis
//        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return shop;
//    }


//    public Shop queryWithLogicalExpire(Long id) {
//        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        // 先查询缓存
//        String shopJson = redisTemplate.opsForValue().get(key);
//        if (StrUtil.isBlank(shopJson)) {
//            // 不存在
//            return null;
//        }
//        // 命中 把json反序列化为对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        // 店铺信息
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        // 过期时间
//        LocalDateTime expireTime = redisData.getExpireTime();
//        // 判断是否过期 （如果过期时间在当前时间之后说明还没有过期）
//        if (expireTime.isAfter(LocalDateTime.now())) {
//            // 未过期
//            return shop;
//        }
//        // 已过期 重建缓存
//        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
//        boolean lock = tryLock(lockKey);
//        // 判断是否获取锁成功
//        if (lock) {
//            // 双重缓存，如果该次查询redis存在，则无需重建缓存
//            String doubleShopJson = redisTemplate.opsForValue().get(key);
//            if (StrUtil.isNotBlank(doubleShopJson)) {
//                // 存在直接返回给前端
//                return JSONUtil.toBean(doubleShopJson, Shop.class);
//            }
//            // 获取成功 开启独立线程，实现缓存重建
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    this.saveShopRedis(id, RedisConstants.CACHE_SHOP_TTL);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    unlock(lockKey);
//                }
//            });
//        }
//        return shop;
//    }

//    public void saveShopRedis(Long id, Long expireSeconds) {
//        //1 查询商铺数据
//        Shop shop = baseMapper.selectById(id);
//        //2 封装逻辑过期时间
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusMinutes(expireSeconds));
//        //3 写入redis
//        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
//    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        // 更新数据库
        baseMapper.updateById(shop);
        // 删除缓存
        redisTemplate.delete(RedisConstants.LOCK_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    /**
     * 通过类型查询店铺
     *
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 判断是否需要根据坐标查询
        if (x == null || y == null) {
            Page<Shop> shopPage = baseMapper.selectPage(
                    new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE),
                    new LambdaQueryWrapper<Shop>().eq(Shop::getTypeId, typeId));
            return Result.ok(shopPage.getRecords());
        }
        // 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 查询redis
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().search(RedisConstants.SHOP_GEO_KEY + typeId,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000), RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().limit(from));
        // 解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>();
        // 截取from - end的部分
        list.stream().skip(from).forEach(result -> {
            // 店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id" + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
