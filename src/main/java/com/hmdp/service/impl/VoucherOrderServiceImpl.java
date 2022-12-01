package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedisIDWorker redisIDWorker;

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Resource
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private IVoucherOrderService proxy;
    private Long voucherId;

    /**
     * PostConstruct 在当前类初始化完毕后执行
     */
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(() -> {
            while (true) {
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        RLock lock = redissonClient.getLock(RedisConstants.ORDER_PREFIX + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder, voucher.getStock());
        } finally {
            lock.unlock();
        }
    }


    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        this.voucherId = voucherId;
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 执行Lua脚本
        Long resultId = redisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
        // resultId如果等于1说明库存不足
        int r = Objects.requireNonNull(resultId).intValue();
        if (r != 0) {
            // 不为0 没有购买资格
            return Result.fail(resultId == 1 ? "库存不足" : "不可重复购买");
        }
        // 为0 有购买资格
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIDWorker.nextId(RedisConstants.ORDER);
        voucherOrder.setId(orderId);
        // 用户ID
        voucherOrder.setUserId(userId);
        // 代金券ID
        voucherOrder.setVoucherId(voucherId);
        // 放入阻塞队列
        orderTasks.add(voucherOrder);
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

//    @Override
//    @Transactional
//    public Result seckillVoucher(Long voucherId) {
//        //1 查询优惠券信息
//        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
//        //2 判断秒杀是否开启，如果开始时间在当前时间之后 说明秒杀还没有开始
//        if (voucher.getCreateTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("优惠券秒杀尚未开始");
//        }
//        //2.1 如果当前时间在优惠券结束时间之后，说明优惠券秒杀已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("优惠券秒杀尚未开始");
//        }
//        //3 判断库存是否充足
//        Integer stock = voucher.getStock();
//        if (stock < 1) {
//            return Result.fail("库存不足");
//        }
//        Long user = UserHolder.getUser().getId();
//        // SimpleRedisLock lock = new SimpleRedisLock(redisTemplate, RedisConstants.ORDER_PREFIX + user);
//        RLock lock = redissonClient.getLock(RedisConstants.ORDER_PREFIX + user);
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            // 获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId, stock);
//        } finally {
//            lock.unlock();
//        }
//
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder, Integer stock) {
        // 一人只可以购买一次
        Long userId = voucherOrder.getUserId();
        LambdaUpdateWrapper<VoucherOrder> countWrapper = new LambdaUpdateWrapper<>();
        // 查询订单
        countWrapper.eq(VoucherOrder::getId, userId).eq(VoucherOrder::getVoucherId, voucherOrder.getVoucherId());
        Integer count = baseMapper.selectCount(countWrapper);
        if (count > 0) {
            log.error("限购一次");
            return;
        }
        //3.1 充足扣减库存
        LambdaUpdateWrapper<SeckillVoucher> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(SeckillVoucher::getStock, stock - 1)
                .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
                .gt(SeckillVoucher::getStock, 0);
        boolean success = iSeckillVoucherService.update(wrapper);
        if (!success) {
            // 扣减失败
            log.error("库存不足");
            return;
        }
        baseMapper.insert(voucherOrder);
    }
}
