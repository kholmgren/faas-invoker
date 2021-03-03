package io.kettil.faasinvoker.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Value;

import java.time.Instant;

@Value
@JsonPropertyOrder({"message", "timestamp"})
public class PingResponse {
    Object message;
    String timestamp = Instant.now().toString();
}
