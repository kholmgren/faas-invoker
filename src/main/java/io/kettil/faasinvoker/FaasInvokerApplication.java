package io.kettil.faasinvoker;

import io.kettil.faas.Manifest;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.File;

@SpringBootApplication
public class FaasInvokerApplication {
    public static void main(String[] args) {
        SpringApplication.run(FaasInvokerApplication.class, args);
    }

    @SneakyThrows
    @Bean
    public Manifest manifest(@Value("${manifest}") String manifestLocation) {
        return Util.yamlMapper().readValue(new File(manifestLocation), Manifest.class);
    }
}
