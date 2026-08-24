package kr.kro.airbob.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("!bulk-write-benchmark & !traffic-benchmark & !test")
@EnableScheduling
public class SchedulingConfig {
}
