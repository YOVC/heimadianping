package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**线程池*/
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);


    @Override
    public Result queryById(Long id) {
        Shop shop = this.queryWithMutex(id);
        if (shop == null){
            return  Result.fail("商铺不存在！");
        }

        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("商铺id不能为空");
        }
        //1.更新数据库
        this.updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }


    /**
     * 解决缓存穿透问题
     * @param id
     * @return
     */
    public Result queryWithPassThrough(Long id) {
        //1.从Redis中查询商铺缓存
        String key=RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            //2.若Redis中存在，返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        //如果数据不为null，那么就为空字符串
        if(shopJson != null){
            return Result.fail("店铺信息不存在");
        }
        //3.若不存在 根据id查询数据库
        Shop shop = this.getById(id);
        //5.若数据不存在，将空数据写入到redis缓存中,返回报错
        if(shop == null){
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return Result.fail("商铺信息不存在!");
        }
        //6.将商铺数据写入Redis中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回商铺信息
        return Result.ok(shop);
    }

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithLogicExpire(Long id) {
        //1.从Redis中查询商铺缓存
        String key=RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){
            //2.如果未命中则直接返回空
            return null;
        }
        //3.如果命中则将json数据转为对象，判断是否过期
        RedisData redisDate = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data =(JSONObject) redisDate.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisDate.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //4.如果未过期，直接返回数据
            return shop;
        }
        //5.如果过期则重建缓存
        //5.1尝试获取锁
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        boolean flag = tryLock(lockKey);
        //5.3获取锁成功,开启独立线程
        if(flag){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    Shop result = this.getById(id);
                    //封装逻辑过期时间和数据
                    RedisData redisDate1=new RedisData();
                    redisDate1.setData(result);
                    redisDate1.setExpireTime(LocalDateTime.now().plusSeconds(30L));
                    //写入redis
                    stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisDate1));
                }catch (Exception e){
                        throw new RuntimeException(e);
                }finally {
                    tryLock(lockKey);
                }
            });
        }
        //3.若不存在 根据id查询数据库
        //5.若数据不存在，将空数据写入到redis缓存中,返回报错
        return shop;
    }

    /**
     * 互斥锁解决缓存击穿
     * @param id 商铺id
     * @return Result
     */
    public Shop queryWithMutex(Long id) {
        //1.从Redis中查询商铺缓存
        String key=RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            //2.若Redis中存在，返回商铺信息
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //如果数据不为null，那么就为空字符串
        if(shopJson != null){
            return null;
        }
        //3.缓存重建
        //3.1尝试获取互斥锁
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {
            boolean flag = this.tryLock(lockKey);
            if(!flag){
                //3.2获取不成功,休眠一段时间再尝试重新获取锁
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //3.3获取成功
            //4.若不存在 根据id查询数据库
            shop = this.getById(id);
            //5.若数据不存在，将空数据写入到redis缓存中,返回报错
            if(shop == null){
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //6.将商铺数据写入Redis中
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            this.unlock(lockKey);
        }
        //返回商铺信息
        return shop;
    }

    /**
     * 获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
