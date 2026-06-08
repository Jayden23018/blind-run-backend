package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置
 *
 * Spring 默认的 SimpleAsyncTaskExecutor 每次调用都创建新线程，不复用，
 * 高并发下会瞬间耗尽内存。改用有界线程池 + CallerRunsPolicy 降级策略。
 *
 * 容量计算（参考值，按实际并发量调整）：
 * - corePoolSize  = 10：平时10个线程处理：派单事件 + 紧急通知 + 短信异步
 * - maxPoolSize   = 50：高峰最多50个线程
 * - queueCapacity = 200：队列满前不扩线程，超过 maxPoolSize 时触发 CallerRunsPolicy
 * - CallerRunsPolicy：队满时由调用线程（Tomcat线程）同步执行，产生自然背压，不丢任务
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
