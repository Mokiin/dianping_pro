package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {


    @Resource
    private ShopServiceImpl service;
    @Resource
    private IShopService shopService;

    @Resource
    private RedisTemplate<String,String> redisTemplate;

    @Test
    void test1() {
        // 查询店铺信息
        List<Shop> list = shopService.list();
        // 店铺分组
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 分批完成写入redis
        map.entrySet().stream().forEach(longListEntry -> {
            // 类型id
            Long typeId = longListEntry.getKey();
            List<Shop> value = longListEntry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> location = new ArrayList<>(value.size());
            for (Shop shop : value) {
                location.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
               // redisTemplate.opsForGeo().add(RedisConstants.SHOP_GEO_KEY + typeId,new Point(shop.getX(),shop.getY()),shop.getId().toString())
            }
            redisTemplate.opsForGeo().add(RedisConstants.SHOP_GEO_KEY + typeId,location);
        });
    }


}
