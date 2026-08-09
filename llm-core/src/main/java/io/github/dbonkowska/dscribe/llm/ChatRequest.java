package io.github.dbonkowska.dscribe.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.dbonkowska.dscribe.conversation.Message;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(String model, List<Message> messages, ResponseFormat response_format) {}
