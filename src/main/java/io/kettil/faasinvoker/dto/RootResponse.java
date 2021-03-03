package io.kettil.faasinvoker.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Value;

import java.util.List;

@Value
@JsonPropertyOrder({"functions"})
public class RootResponse {

    List<Registration> functions;
}
