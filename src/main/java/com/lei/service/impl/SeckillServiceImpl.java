package com.lei.service.impl;

import com.lei.dao.SeckillDao;
import com.lei.dao.SuccessKilledDao;
import com.lei.dao.cache.RedisDao;
import com.lei.dto.Exposer;
import com.lei.dto.SeckillExecution;
import com.lei.entity.Seckill;
import com.lei.entity.SuccessKilled;
import com.lei.enums.SeckillStatEnum;
import com.lei.exception.RepeatKillException;
import com.lei.exception.SeckillCloseException;
import com.lei.exception.SeckillException;
import com.lei.service.SeckillService;
import org.apache.commons.collections.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;


import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SeckillServiceImpl implements SeckillService {

    private Logger logger = LoggerFactory.getLogger(this.getClass());
    @Autowired
    private SuccessKilledDao successKilledDao;
    @Autowired
    private SeckillDao seckillDao;

    @Autowired
    private RedisDao redisDao;
    //md5盐值字符串
    private final String slat="abcdefg123456";
    @Override
    public List<Seckill> getSeckillList() {
        return seckillDao.queryAll(0,4);
    }

    @Override
    public Seckill getById(long seckillId) {
        return seckillDao.queryById(seckillId);
    }

    //暴露秒杀接口
    @Override
    public Exposer exportSeckillUrl(long seckillId) {

        //优化点：缓存优化
        //1 访问redis
        Seckill seckill=redisDao.getSeckill(seckillId);
        if(seckill == null){
            //2 访问数据库
            seckill=seckillDao.queryById(seckillId);
            if(seckill==null){
                return new Exposer(false,seckillId);

            }else {
                //3 放入redis
                redisDao.putSeckill(seckill);
            }
        }

        if(seckill==null){
            return new Exposer(false,seckillId);
        }
        Date startTime=seckill.getStartTime();
        Date endTime =seckill.getEndTime();
        Date nowTime=new Date();
        if(nowTime.getTime()<startTime.getTime()||nowTime.getTime()>endTime.getTime()){
            return new Exposer(false,seckillId,nowTime.getTime(),startTime.getTime(),endTime.getTime());
        }
        //转化特定字符串的过程，不可逆
        String md5=getMD5(seckillId);
        return new Exposer(true,md5,seckillId);
    }
    private String getMD5(long seckillId){
        String base=seckillId+"/"+slat;
        String md5= DigestUtils.md5DigestAsHex(base.getBytes());
        return md5;
    }
    @Override
    @Transactional
    /*使用注解控制事务方法的优点*/
    /**
     * 1、开发团队达成一致约定，明确标注事务方法的编程风格；
     * 2、保证事务方法的执行时间尽可能短，不要穿插其他网络操作（Redis/http请求等）
     * 3、不是所有的方法都需要事务，如只有一条修改操作，只读操作，不需要事务控制
     */
    public SeckillExecution executeSeckill(long seckillId, long userPhone, String md5) throws SeckillException, RepeatKillException, SeckillCloseException {
        if(md5==null || !md5.equals(getMD5(seckillId))){
            throw new SeckillException("seckill data rewrite");
        }
        //执行秒杀逻辑: 减少库存 + 记录购买行为
        Date nowTime=new Date();

        try {
            //记录购买行为，
            int insertCount = successKilledDao.insertSuccessKilled(seckillId, userPhone);
            if (insertCount <= 0) {
                //重读秒杀,抛出重复秒杀异常
                throw new RepeatKillException("seckill repeat");
            } else {
                //减少库存，热点商品竞争
                int updateCount = seckillDao.reduceNumber(seckillId, nowTime);
                if (updateCount <= 0) {
                    //没有更新到dao操作,，秒杀结束,rollback
                    throw new SeckillCloseException("seckill is closed");
                } else {
                    //秒杀成功 commit
                    //返回SeckillExecution
                    SuccessKilled successKilled = successKilledDao.queryByIdWithSeckill(seckillId, userPhone);
                    return new SeckillExecution(seckillId, SeckillStatEnum.SUCCESS,successKilled);
                }

            }


        }catch (SeckillCloseException e1){
            throw e1;
        }catch (RepeatKillException e2){
            throw e2;
        }catch (Exception e){
            logger.error(e.getMessage(),e);
            //所有编译器异常，转化为运行期异常
            throw new SeckillException("seckill inner error:"+e.getMessage());
        }
    }

    @Override
    public SeckillExecution executeSeckillProducer(long seckillId, long userPhone, String md5) throws SeckillException, RepeatKillException, SeckillCloseException {
        if (md5 == null || !md5.equals(getMD5(seckillId))) {
            return new SeckillExecution(seckillId, SeckillStatEnum.DATA_REWRITE);
        }
        Date killTime = new Date();
        Map<String, Object> map = new HashMap<>();
        map.put("seckillId", seckillId);
        map.put("phone", userPhone);
        map.put("killTime", killTime);
        map.put("result", null);
        // 执行储存过程，result被复制
        try {
            seckillDao.killByProducer(map);
            // 获取result
            int result = MapUtils.getInteger(map, "result", -2);
            if (result == 1) {
                SuccessKilled successKilled = successKilledDao.queryByIdWithSeckill(seckillId, userPhone);
                return new SeckillExecution(seckillId, SeckillStatEnum.SUCCESS, successKilled);
            } else {
                return new SeckillExecution(seckillId, SeckillStatEnum.stateOf(result));
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return new SeckillExecution(seckillId, SeckillStatEnum.INNER_ERROR);
        }
    }
}
