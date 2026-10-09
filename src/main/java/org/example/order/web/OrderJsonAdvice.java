package org.example.order.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Reject numeric enums for Order without changing JSON handling in other modules. */
@ControllerAdvice(assignableTypes = OrderController.class)
public class OrderJsonAdvice extends RequestBodyAdviceAdapter {
    private final ObjectMapper json;

    public OrderJsonAdvice(ObjectMapper shared) {
        json = shared.copy().enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    }

    @Override public boolean supports(MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return parameter.getContainingClass() == OrderController.class;
    }

    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter,
            Type targetType, Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] body = input.getBody().readAllBytes();
        try {
            json.readerFor(json.constructType(targetType)).readValue(body);
        } catch (IOException error) {
            throw new HttpMessageNotReadableException("JSON hoặc enum không hợp lệ.", error, input);
        }
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return new ByteArrayInputStream(body); }
            @Override public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }
}
