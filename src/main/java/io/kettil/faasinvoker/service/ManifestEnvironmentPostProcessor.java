package io.kettil.faasinvoker.service;

import io.kettil.faas.Manifest;
import io.kettil.faasinvoker.Util;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static java.util.stream.Collectors.joining;

public class ManifestEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path manifestPath = Path.of(environment.getProperty("manifest", "/etc/faas/manifest.yaml"));
        if (Files.notExists(manifestPath)) {
            System.err.printf("Manifest file %s is missing%n", manifestPath);
            System.exit(1);
        }

        Manifest manifest = null;
        try {
            manifest = Util.yamlMapper()
                .readValue(manifestPath.toFile(), Manifest.class);
        } catch (IOException e) {
            System.err.printf("Cannot parse manifest file %s: %s%n",
                manifestPath, e.getMessage());
            System.exit(2);
        }

        Path jarPath = Path.of(environment.getProperty("jar", "./" + Path.of(manifest.getLocation()).getFileName()));
        if (Files.notExists(jarPath)) {
            System.err.printf("Jar file %s is missing%n", jarPath);
            System.exit(3);
        }

        Map<String, Object> properties = new HashMap<>();
        properties.put("manifest", manifestPath.toString());

        properties.put("jar", jarPath.toString());
        properties.put("spring.cloud.function.location", jarPath.toString());

        properties.put("spring.cloud.function.function-class",
            manifest.getPaths().values().stream()
                .map(Manifest.PathManifest::getHandler)
                .collect(joining(";")));

        environment.getPropertySources()
            .addLast(new MapPropertySource(manifestPath.toString(), properties));
    }
}
