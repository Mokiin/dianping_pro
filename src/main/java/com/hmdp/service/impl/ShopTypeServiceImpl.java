package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Override
    public Result queryShopType() {
        // 先查询redis缓存
        String shopJson =  redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY);
        if (StrUtil.isNotBlank(shopJson)){
            // 如果redis缓存中有则直接返回
            List<ShopType> typeList = JSONUtil.toList(shopJson, ShopType.class);
            return Result.ok(typeList);
        }
        // 没有 查询数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY,JSONUtil.toJsonStr(shopTypes));
        return Result.ok(shopTypes);
    }
}
