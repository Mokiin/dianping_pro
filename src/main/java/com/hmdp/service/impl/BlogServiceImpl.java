package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        LambdaQueryWrapper<Blog> wrapper = new LambdaQueryWrapper<>(new Blog());
        wrapper.orderByDesc(Blog::getLiked);
        // 根据用户查询
        Page<Blog> page = baseMapper.selectPage(new Page<>(current, SystemConstants.MAX_PAGE_SIZE), wrapper);
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 查询blog
        Blog blog = baseMapper.selectById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        // 发blog的用户
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        // 判断当前用户是否已经点赞
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> top5 = redisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 != null) {
            List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
            String idStr = StrUtil.join(",", ids);
            // 前五点赞用户
            List<UserDTO> userDTOS = userService.query().in("id", ids).last("ORDER BY FIELD(id" + idStr + ")").list()
                    .stream()
                    .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                    .collect(Collectors.toList());

            return Result.ok(userDTOS);
        }
        return Result.ok(Collections.emptyList());
    }

    @Override
    public Result likeBlog(Long id) {
        // 当前登录用户
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 判断当前用户是否已经点赞
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        // 通过前端返回的id获得当前博客
        Blog blog = baseMapper.selectById(id);
        // 为false说明没有点赞过 允许点赞
        if (score == null) {
            LambdaUpdateWrapper<Blog> wrapper = new LambdaUpdateWrapper<>();
            // 将当前博客的点赞量 + 1
            wrapper.set(Blog::getLiked, blog.getLiked() + 1).eq(Blog::getId, id);
            // 修改点赞数量
            int update = baseMapper.update(blog, wrapper);
            if (update >= 1) {
                // 保存用户到redis的set集合
                redisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
                return Result.ok("点赞成功");
            }
        } else {
            // 取消点赞
            LambdaUpdateWrapper<Blog> wrapper = new LambdaUpdateWrapper<>();
            // 将当前博客的点赞量 - 1
            wrapper.set(Blog::getLiked, blog.getLiked() - 1).eq(Blog::getId, id);
            // 修改点赞数量
            int update = baseMapper.update(blog, wrapper);
            if (update >= 1) {
                // 将用户从redis的set集合移除
                redisTemplate.opsForZSet().remove(key, userId.toString());
                return Result.ok("取消点赞");
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogByUserId(Long id, Integer current) {
        LambdaQueryWrapper<Blog> queryWrapper = new LambdaQueryWrapper<Blog>().eq(Blog::getUserId, id);
        Page<Blog> page = baseMapper.selectPage(new Page<>(current, SystemConstants.MAX_PAGE_SIZE), queryWrapper);
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        int isSuccess = baseMapper.insert(blog);
        if (isSuccess < 0) {
            return Result.fail("新增笔记失败");
        }
        // 查询笔记作者的所有粉丝
        List<Follow> allFans = followService.queryAllFans(user);
        // 推送笔记id给所有粉丝
        allFans.stream().forEach(follow -> {
            // 粉丝id
            Long userId = follow.getUserId();
            // 推送
            redisTemplate.opsForZSet().add(RedisConstants.FEED_KEY + userId, blog.getId().toString(), System.currentTimeMillis());
        });
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 查询收件箱
        Set<ZSetOperations.TypedTuple<String>> typedTuples =
                redisTemplate.opsForZSet().reverseRangeByScoreWithScores(RedisConstants.FEED_KEY + userId, 0, max, offset, 2);
        // 非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 解析数据
        ArrayList<Long> ids = new ArrayList<>(typedTuples.size());
        long  minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 获取id
            ids.add(Long.valueOf(Objects.requireNonNull(typedTuple.getValue())));
            // 获取时间戳
            long time = Objects.requireNonNull(typedTuple.getScore()).longValue();
            if (time == minTime) {
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }
        // 根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id" + idStr + ")").list();

        blogs.stream().forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });

        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
