package com.juanluidos.ticketing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Un solo hilo a propósito. La extracción va contra una única GPU, que
     * atiende de una en una: lanzar dos en paralelo no acelera nada y en la
     * GTX 1070 de 8 GB puede provocar que el modelo se descargue y se recargue
     * entre peticiones. La cola las serializa.
     */
    @Bean("extractionExecutor")
    public TaskExecutor extractionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("extraccion-");
        executor.initialize();
        return executor;
    }
}
