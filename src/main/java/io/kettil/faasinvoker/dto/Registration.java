package io.kettil.faasinvoker.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Value;

@Value
@JsonPropertyOrder({"name", "arg", "return"})
public class Registration {
    String name;
    String arg;
    @JsonProperty("return")
    String returns;
}
