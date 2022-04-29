package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogic(String key,Object value,Long time,TimeUnit unit){
        RedisData redisDate=new RedisData();
        redisDate.setData(value);
        redisDate.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisDate));
    }

    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key=keyPrefix+id;
        //1.从redis中查询数据
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(json)){
            //3.存在直接返回
            return JSONUtil.toBean(json, type);
        }
        //4.判断是否是空值
        if(json != null){
            return null;
        }

        //5.查询数据库
        R r = dbFallback.apply(id);
        //6.判断数据库中是否存在该数据
        if(r == null){
            //7.不存在，则将空值写入redis，并返回错误信息
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return  null;
        }
        //8.将数据写入到Redis中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r),time,unit );
        return r;
    }


    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit){
       String key=keyPrefix+id;
        //1.从Redis中查询数据
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否为空
        if(StrUtil.isBlank(json)){
            //3.如果为空则直接返回错误信息
            return null;
        }
        //4.将json数据转为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否逻辑过期
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //6.重建缓存
        //6.1获取锁
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        boolean flag = tryLock(lockKey);
        //6.2判断获取锁是否成功
        if(flag){
         //7.获取成功，开启独立线程，缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //7.1 查询数据库
                    R r1 = dbFallback.apply(id);
                    //7.2 写入redis
                    setWithLogic(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //7.3释放锁
                    unlock(lockKey);
                }
            });
        }
        return r;
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
