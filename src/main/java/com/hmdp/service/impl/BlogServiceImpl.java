package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.text.StrJoiner;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        LambdaQueryWrapper<Blog> wrapper = new LambdaQueryWrapper<>(new Blog());
        wrapper.orderByDesc(Blog::getLiked);
        Page<Blog> page = baseMapper.selectPage(new Page<>(current, SystemConstants.MAX_PAGE_SIZE), wrapper);
        // 根据用户查询
//        Page<Blog> page = query()
//                .orderByDesc("liked")
//                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
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

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
