package com.hmdp;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.net.InetAddress;

@MapperScan("com.hmdp.mapper")
@SpringBootApplication
@Slf4j
public class HmDianPingApplication {

    @Value("${server.port}")
    public String port;

    public static void main(String[] args)  {
        SpringApplication.run(HmDianPingApplication.class, args);
    }

    @Bean
    public CommandLineRunner printServerUrl() {
        return args -> {
            String hostAddress = InetAddress.getLocalHost().getHostAddress();
            log.info("---------------------------------------------------------------");
//            log.info("应用启动成功！访问地址：http://{}:{}", hostAddress, port);
            log.info("应用启动成功！访问地址：http://{}:{}", hostAddress, "8080");
            log.info("---------------------------------------------------------------");
        };
    }

}
