package io.kettil.faasinvoker.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Value;
import org.springframework.http.HttpStatus;

import java.time.Instant;

@Value
@JsonPropertyOrder({"error", "message", "path", "requestId", "status", "timestamp"})
public class ErrorResponse {
    String error;
    String message;
    String path;
    String requestId = "TODO: implement";
    int status;
    String timestamp = Instant.now().toString();

    public static ErrorResponse newErrorResponse(HttpStatus status, String message, String path) {
        return new ErrorResponse(status.getReasonPhrase(), message, path, status.value());
    }
}
