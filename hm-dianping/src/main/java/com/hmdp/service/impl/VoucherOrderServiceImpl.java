package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWork;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWork redisIdWork;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 实现秒杀下单优惠卷
     * @param voucherId 优惠劵id
     * @return  订单id
     */
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠卷信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀活动还未开始");
        }
        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀活动已结束");
        }
        //4.判断库存是否充足
        if (voucher.getStock()<1){
            return Result.fail("优惠价库存不足");
        }
        return createVoucherOrder(voucherId);
    }


    private Result createVoucherOrder(Long voucherId){
        //一人一单
        Long userId=UserHolder.getUser().getId();
        //创建锁对象
        SimpleRedisLock redisLock=new SimpleRedisLock(stringRedisTemplate, "order:"+userId+":"+voucherId);
        //尝试获取锁
        boolean isLock = redisLock.tryLock(1200);
        //判断是否获取锁成功
        if (!isLock){
            //获取锁失败，则返回错误信息
            return Result.fail("禁止重复下单!");
        }
        try {
            int count = this.query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0){
                return Result.fail("改用户已经购买过");
            }
            //5.扣减库存
            boolean flag = seckillVoucherService.update()
                    .setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock",0)
                    .update();
            if (!flag){
                return Result.fail("优惠卷库存不足");
            }
            //6.生成订单
            VoucherOrder voucherOrder=new VoucherOrder();
            //6.1订单Id
            long orderId = redisIdWork.nextId("order");
            voucherOrder.setId(orderId);
            //6.2用户id
            voucherOrder.setUserId(userId);
            //6.3代金卷id
            voucherOrder.setVoucherId(voucherId);
            this.save(voucherOrder);
            return Result.ok(orderId);
        } finally {
            redisLock.unlock();
        }


    }


    /*
    private Result createVoucherOrder(Long voucherId){
        //一人一单
        Long userId=UserHolder.getUser().getId();
        synchronized (userId.toString().intern()){
            int count = this.query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0){
                return Result.fail("改用户已经购买过");
            }
            //5.扣减库存
            boolean flag = seckillVoucherService.update()
                    .setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock",0)
                    .update();
            if (!flag){
                return Result.fail("优惠卷库存不足");
            }
            //6.生成订单
            VoucherOrder voucherOrder=new VoucherOrder();
            //6.1订单Id
            long orderId = redisIdWork.nextId("order");
            voucherOrder.setId(orderId);
            //6.2用户id
            voucherOrder.setUserId(userId);
            //6.3代金卷id
            voucherOrder.setVoucherId(voucherId);
            this.save(voucherOrder);
            return Result.ok(orderId);
        }

    }
    */
}
