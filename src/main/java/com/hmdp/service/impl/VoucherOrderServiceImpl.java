package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedisIDWorker redisIDWorker;

    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        //1 查询优惠券信息
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        //2 判断秒杀是否开启，如果开始时间在当前时间之后 说明秒杀还没有开始
        if (voucher.getCreateTime().isAfter(LocalDateTime.now())) {
            return Result.fail("优惠券秒杀尚未开始");
        }
        //2.1 如果当前时间在优惠券结束时间之后，说明优惠券秒杀已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("优惠券秒杀尚未开始");
        }
        //3 判断库存是否充足
        Integer stock = voucher.getStock();
        if (stock < 1){
            return Result.fail("库存不足");
        }
        //3.1 充足扣减库存
        LambdaUpdateWrapper<SeckillVoucher> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(SeckillVoucher::getStock,stock - 1);
        wrapper.eq(SeckillVoucher::getVoucherId, voucherId);
        boolean success = iSeckillVoucherService.update(wrapper);
        if (!success){
            // 扣减失败
            return Result.fail("库存不足");
        }
        //3.2 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单ID
        long orderId = redisIDWorker.nextId(SystemConstants.SESSION_CODE);
        voucherOrder.setId(orderId);
        // 用户ID
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 优惠券ID
        voucherOrder.setVoucherId(voucherId);
        baseMapper.insert(voucherOrder);
        return Result.ok(orderId);
    }
}
