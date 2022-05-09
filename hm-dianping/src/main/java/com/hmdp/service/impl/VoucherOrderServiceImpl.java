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
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWork redisIdWork;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //线程池
    private final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //线程任务
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                //1.获取队列中的订单信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    createVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常",e);
                }
            }

        }
    }

    private void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //创建锁对象
        RLock redisLock = redissonClient.getLock("order:" + userId + ":" + voucherId);
        //尝试获取锁
        boolean isLock = redisLock.tryLock();
        //判断是否获取锁成功
        if (!isLock){
            //获取锁失败，则返回错误信息
            log.error("禁止重复下单!");
        }
        try {
            int count = this.query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0){
                log.error("不允许重复下单");

            }
            //5.扣减库存
            boolean flag = seckillVoucherService.update()
                    .setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock",0)
                    .update();
            if (!flag){
                log.error("优惠券库存不足");
            }

            //创建订单
            this.save(voucherOrder);
        } finally {
            redisLock.unlock();
        }

    }

    /**
     * 实现秒杀下单优惠卷
     * @param voucherId 优惠劵id
     * @return  订单id
     */
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate
                .execute(SECKILL_SCRIPT, Collections.emptyList(),
                        voucherId.toString(), userId.toString());
        int r = result.intValue();
        //2.判断结果是否为0
        if(r != 0){
            // 2.1 不为0，则说明没有购买资格
            return Result.fail(r == 1 ? "库存不足":"不能重复下单");
        }
        //2.2 为0，则说明由购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder=new VoucherOrder();
        long orderId = redisIdWork.nextId("order");
        voucherOrder.setId(orderId);
        //2.3用户id
        voucherOrder.setUserId(userId);
        //2.4代金券id
        voucherOrder.setVoucherId(voucherId);
        //2.5保存阻塞队列
        orderTasks.add(voucherOrder);
        //3.返回订单id
        return Result.ok(orderId);
    }



//    /**
//     * 实现秒杀下单优惠卷
//     * @param voucherId 优惠劵id
//     * @return  订单id
//     */
//    @Override
//    @Transactional
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠卷信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀活动还未开始");
//        }
//        //3.判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀活动已结束");
//        }
//        //4.判断库存是否充足
//        if (voucher.getStock()<1){
//            return Result.fail("优惠价库存不足");
//        }
//        return createVoucherOrder(voucherId);
//    }
//
//
//    private Result createVoucherOrder(Long voucherId){
//        //一人一单
//        Long userId=UserHolder.getUser().getId();
//        //创建锁对象
//        RLock redisLock = redissonClient.getLock("order:" + userId + ":" + voucherId);
//        //尝试获取锁
//        boolean isLock = redisLock.tryLock();
//        //判断是否获取锁成功
//        if (!isLock){
//            //获取锁失败，则返回错误信息
//            return Result.fail("禁止重复下单!");
//        }
//        try {
//            int count = this.query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//            if (count > 0){
//                return Result.fail("改用户已经购买过");
//            }
//            //5.扣减库存
//            boolean flag = seckillVoucherService.update()
//                    .setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock",0)
//                    .update();
//            if (!flag){
//                return Result.fail("优惠卷库存不足");
//            }
//            //6.生成订单
//            VoucherOrder voucherOrder=new VoucherOrder();
//            //6.1订单Id
//            long orderId = redisIdWork.nextId("order");
//            voucherOrder.setId(orderId);
//            //6.2用户id
//            voucherOrder.setUserId(userId);
//            //6.3代金卷id
//            voucherOrder.setVoucherId(voucherId);
//            this.save(voucherOrder);
//            return Result.ok(orderId);
//        } finally {
//            redisLock.unlock();
//        }
//
//
//    }


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
