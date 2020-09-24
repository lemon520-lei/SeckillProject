package com.lei.dao.cache;

import com.dyuproject.protostuff.LinkedBuffer;
import com.dyuproject.protostuff.ProtostuffIOUtil;
import com.dyuproject.protostuff.runtime.RuntimeSchema;
import com.lei.entity.Seckill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class RedisDao {

    private final Logger logger= LoggerFactory.getLogger(this.getClass());
    private JedisPool jedisPool;
    public RedisDao(String ip,int port){
        jedisPool =new JedisPool(ip,port);
    }

    private RuntimeSchema<Seckill> schema =RuntimeSchema.createFrom(Seckill.class);

    public Seckill getSeckill(long seckillId){
        //缓存Redis操作逻辑
        try{
            Jedis jedis=jedisPool.getResource();

            try{
                String key="seckill:"+seckillId;
                //并没有实现序列化操作
                //get ->byte[] ->反序列化->Object（seckill）
                //采用自定义序列化  protostuff : pojo
            byte[] bytes=jedis.get(key.getBytes());
            if(bytes!=null){
                Seckill seckill=schema.newMessage();
                ProtostuffIOUtil.mergeFrom(bytes,seckill,schema);
                //seckill被反序列化
                return seckill;
            }

            }finally {
                jedis.close();
            }


        }catch (Exception ex){
            logger.error(ex.getMessage(),ex);
        }
        return null;
    }

    //传递到Redis中
    public String putSeckill(Seckill seckill){
        //set Object（seckill）-> 反序列化-->byte[]
        try{
            Jedis jedis=jedisPool.getResource();
            try{
                String key="seckill:"+seckill.getSeckillId();
                byte[] bytes=ProtostuffIOUtil.toByteArray(seckill,schema,
                        LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE));
                //超时缓存
                int timeout=60*60;//1小时
                String result=jedis.setex(key.getBytes(),timeout,bytes);
                return result;
            }finally {
                jedis.close();
            }
        }catch (Exception e){
            logger.error(e.getMessage(),e);
        }finally {

        }
        return null;
    }
}
