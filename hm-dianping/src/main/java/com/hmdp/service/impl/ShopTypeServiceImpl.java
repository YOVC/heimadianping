package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        //1.从Redis中读取商铺类型数据
        String key=RedisConstants.CACHE_SHOP_KEY+"type";
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopTypeJson)){
            //2.如果Redis存在商铺类型的缓存
            List<ShopType> shopTypes = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypes);
        }
        //3.如果不存在，则从数据库中查询数据
        List<ShopType> typeList = this.query().orderByAsc("sort").list();
        //4.如果数据库中不存在数据，则返回异常
        if(typeList.isEmpty()){
            return Result.fail("商铺类型信息不存在");
        }
        //5.将数据保存在Redis缓存中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(typeList),RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        //6.返回商铺类型信息
        return Result.ok(typeList);
    }
}
