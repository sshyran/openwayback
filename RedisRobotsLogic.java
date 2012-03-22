package org.archive.wayback.accesscontrol.robotstxt;

import java.util.List;
import java.util.logging.Logger;

import org.archive.wayback.webapp.PerformanceLogger;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

public class RedisRobotsLogic {
	
	private final static Logger LOGGER = 
		Logger.getLogger(RedisRobotsLogic.class.getName());
	
	private RedisConnectionManager redisConn;
	
	RedisRobotsLogic(RedisConnectionManager redisConn)
	{
		this.redisConn = redisConn;		
	}

	public <T> T runJedisCmd(JedisRunner<T> runner)
	{
		Jedis jedis = null;
		
		try {
			jedis = redisConn.getJedisInstance();
			return runner.run(jedis);
		} catch (JedisConnectionException jce) {
			LOGGER.severe("Jedis Exception: " + jce);
			redisConn.returnBrokenJedis(jedis);
			jedis = null;
		} finally {
			redisConn.returnJedisInstance(jedis);
		}
		
		return null;
	}
	
	public void runJedisCmd(JedisRunnerVoid runner)
	{
		Jedis jedis = null;
		
		try {
			jedis = redisConn.getJedisInstance();
			runner.run(jedis);
		} catch (JedisConnectionException jce) {
			LOGGER.severe("Jedis Exception: " + jce);
			redisConn.returnBrokenJedis(jedis);
			jedis = null;
		} finally {
			redisConn.returnJedisInstance(jedis);
		}
	}
	
	static class RedisValue
	{
		String value;
		long ttl;
		
		RedisValue(String value, long ttl)
		{
			this.value = value;
			this.ttl = ttl;
		}
	}
	
	public RedisValue getValue(final String key)
	{
		long startTime = System.currentTimeMillis();
		RedisValue value = this.runJedisCmd(new JedisRunner<RedisValue>()
		{
			public RedisValue run(Jedis jedis)
			{
				String value = jedis.get(key);
				if (value == null) {
					return null;
				}
				long ttl = jedis.ttl(key);
				return new RedisValue(value, ttl);
			}
		});
		PerformanceLogger.noteElapsed("RedisGetTTL", System.currentTimeMillis() - startTime, ((value == null) ? "REDIS MISS: " : "REDIS HIT: ") + key);
		return value;
	}
	
	public void updateValue(final String url, final RedisValue value)
	{
		this.runJedisCmd(new JedisRunnerVoid()
		{
			public void run(Jedis jedis)
			{
				if (value.value == null) {
					jedis.expire(url, (int)value.ttl);
				} else {
					jedis.setex(url, (int)value.ttl, value.value);
				}
			}
		});
	}
	
	public void pushKey(final String list, final String key)
	{
		this.runJedisCmd(new JedisRunnerVoid()
		{
			public void run(Jedis jedis)
			{
				jedis.rpush(list, key);				
			}
		});
	}
	
	public void pushKey(final String list, final String key, final int maxSize)
	{
		this.runJedisCmd(new JedisRunnerVoid()
		{
			public void run(Jedis jedis)
			{
				if (jedis.llen(list) <= maxSize) {
					jedis.rpush(list, key);
				}
			}
		});
	}
	
	public String popKey(final String list)
	{
		return this.runJedisCmd(new JedisRunner<String>()
		{
			public String run(Jedis jedis)
			{
				List<String> values = jedis.blpop(0, list);
				return values.get(1);
			}
		});
	}
	
	public void close()
	{
		redisConn.close();
	}
	
	interface JedisRunner<T>
	{
		public T run(Jedis jedis);
	}
	
	interface JedisRunnerVoid
	{
		public void run(Jedis jedis);
	}
}
