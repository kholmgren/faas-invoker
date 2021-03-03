package io.kettil.faasinvoker.service;

import io.kettil.faas.Manifest;
import io.kettil.faasinvoker.dto.ErrorResponse;
import io.kettil.faasinvoker.dto.PingResponse;
import io.kettil.faasinvoker.dto.Registration;
import io.kettil.faasinvoker.dto.RootResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.cloud.function.context.catalog.SimpleFunctionRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.*;

import java.util.function.Function;

import static java.util.stream.Collectors.toList;
import static org.springframework.util.StringUtils.uncapitalize;
import static org.springframework.util.StringUtils.unqualify;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvokerService {
    private final Manifest manifest;
    private final FunctionCatalog catalog;

    @Bean
    public RouterFunction<ServerResponse> routeRequest() {
        return RouterFunctions.route(RequestPredicates.GET("").or(RequestPredicates.GET("/")), root())
            .andRoute(RequestPredicates.GET("/ping"), ping())
            .andRoute(RequestPredicates.all(), function());
    }

    private HandlerFunction<ServerResponse> root() {
        return request -> ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RootResponse(catalog.getNames(Function.class).stream()
                .map(name -> (SimpleFunctionRegistry.FunctionInvocationWrapper) catalog.lookup(name))
                .filter(wrapper -> !wrapper.getTarget().getClass().getCanonicalName().startsWith("org.springframework"))
                .map(wrapper -> new Registration(
                    wrapper.getTarget().getClass().getCanonicalName(),
                    wrapper.getRawInputType().getCanonicalName(),
                    wrapper.getRawOutputType().getCanonicalName()))
                .collect(toList())));
    }

    private HandlerFunction<ServerResponse> ping() {
        return request -> ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new PingResponse("pong"));
    }

    private HandlerFunction<ServerResponse> function() {
        return request -> {
            if (request.method() != HttpMethod.POST)
                return ServerResponse.status(
                    HttpStatus.METHOD_NOT_ALLOWED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(ErrorResponse.newErrorResponse(
                        HttpStatus.METHOD_NOT_ALLOWED,
                        String.format("Invalid method '%s'; functions require POST", request.method()),
                        request.path()));

            String authorization = request.headers().asHttpHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            log.info("Authorization: {}", authorization);

            String path = request.uri().getPath();
            Manifest.PathManifest pathManifest = manifest.getPaths().get(path);
            if (pathManifest == null)
                return ServerResponse.status(
                    HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(ErrorResponse.newErrorResponse(
                        HttpStatus.NOT_FOUND,
                        String.format("No function configured for path '%s'", path),
                        request.path()));

            String functionName = uncapitalize(unqualify(pathManifest.getHandler()));
            SimpleFunctionRegistry.FunctionInvocationWrapper wrapper = catalog.lookup(functionName);

            if (wrapper == null)
                return ServerResponse.status(
                    HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(ErrorResponse.newErrorResponse(
                        HttpStatus.NOT_FOUND,
                        String.format("No function implementation '%s' registered for path %s",
                            functionName, path),
                        request.path()));

            return request.bodyToMono(wrapper.getRawInputType())
                .flatMap(input ->
                {
                    try {
                        return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(wrapper.apply(input));

                    } catch (Exception e) {
                        return ServerResponse.status(
                            HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(ErrorResponse.newErrorResponse(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                String.format("Error for input '%s': %s",
                                    input, e.getMessage()),
                                request.path()));
                    }
                });
        };
    }
}
