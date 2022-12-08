package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 判断关注还是取关
        if (isFollow) {
            // 关注
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            int insert = baseMapper.insert(follow);
            if (insert > 0) {
                // 把关注用户的id放入redis
                redisTemplate.opsForSet().add(RedisConstants.FOLLOWS + userId, followUserId.toString());
            }
        } else {
            // 取关
            LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, followUserId).eq(Follow::getUserId, userId);
            baseMapper.delete(queryWrapper);
            redisTemplate.opsForSet().remove(RedisConstants.FOLLOWS + userId, followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId);
        // 查询是否关注
        Long count = baseMapper.selectCount(queryWrapper);
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 求交集
        String key = RedisConstants.FOLLOWS;
        Set<String> intersect = redisTemplate.opsForSet().intersect(key + userId, key + id);
        if (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        // 解析Id集合
        List<Long> collect = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserHolder> users = userService.listByIds(collect)
                .stream().map(user -> BeanUtil.copyProperties(user, UserHolder.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public List<Follow> queryAllFans(UserDTO user) {
        return baseMapper.selectList(new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, user.getId()));
    }
}
