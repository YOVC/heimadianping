package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.手机号如果不符合
            return Result.fail("手机号格式错误");
        }
        //3.生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到Redis
        stringRedisTemplate.opsForValue()
                .set(RedisConstants.LOGIN_CODE_KEY+phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码
        log.debug("发送短信验证码成功,验证码:{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.手机号如果不符合
            return Result.fail("手机号格式错误");
        }
        //3.从Redis中获取校验码
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //4.根据手机号查询用户
        User user = this.query().eq("phone", phone).one();
        //5.用户不存在
        if(user == null){
            //6.创建并保存用户
            user = createUserByPhone(phone);
        }

        //7.保存用户信息到Redis这
        //7.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //7.2 将user对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, String> userMap =new HashMap<>(3);
        userMap.put("id",userDTO.getId().toString());
        userMap.put("nickName", userDTO.getNickName());
        userMap.put("icon",userDTO.getIcon());
        //7.3 存储
        String tokenKey=RedisConstants.LOGIN_USER_KEY+ token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //7.4 设置token有效期
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_TOKEN_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1.获取用户
        Long userId = UserHolder.getUser().getId();
        //2.获取当前日期
        LocalDateTime dateTime = LocalDateTime.now();
        //3.拼接key
        String keySuffix = dateTime.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = dateTime.getDayOfMonth();
        //5.写入redis SETBIT KEY offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取用户
        Long userId = UserHolder.getUser().getId();
        //2.获取当前日期
        LocalDateTime dateTime = LocalDateTime.now();
        //3.拼接key
        String keySuffix = dateTime.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = dateTime.getDayOfMonth();
        //5.获取本月截至今天为止所有的签到记录，返回的是一个十进制数字
        List<Long> results = stringRedisTemplate.opsForValue()
                .bitField(key,
                        BitFieldSubCommands.create()
                                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if(results == null && results.isEmpty()){
            return Result.ok(0);
        }
        Long num = results.get(0);
        if(num == null && num == 0){
            return Result.ok(0);
        }

        //6.循环遍历
        int count = 0;
        while (true){
            //6.1让数字与1进行与运算，若为0，则说明改天未签到，结束
            if((num & 1) == 0){
                break;
            }else {
                //6.2如果不为0，则说明已经签到，计数器+1
                count++;
            }
            //6.3把数字右移一位，抛弃最后一位bit
            num >>>= 1;
        }
        return Result.ok(count);
    }


    private User createUserByPhone(String phone) {
        //创建用户
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //保存用户
        this.save(user);
        return user;
    }
}
