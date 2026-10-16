package com.taskflow.common.error;

import com.taskflow.auth.EmailAlreadyRegisteredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (var error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(new ApiError(400, "Validation failed", request.getRequestURI(), fields));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> invalidParameters(HandlerMethodValidationException exception, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (var result : exception.getParameterValidationResults()) {
            String name = result.getMethodParameter().getParameterName();
            for (var error : result.getResolvableErrors()) {
                fields.putIfAbsent(name == null ? "parameter" : name, error.getDefaultMessage());
            }
        }
        return ResponseEntity.badRequest().body(new ApiError(400, "Validation failed", request.getRequestURI(), fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class, ConstraintViolationException.class})
    ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Invalid request body or parameters", request);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "Task not found", request);
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    ResponseEntity<ApiError> emailConflict(EmailAlreadyRegisteredException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "Email is already registered", request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ApiError> invalidCredentials(BadCredentialsException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(new ApiError(401, "Authentication is required or the access token is invalid",
                        request.getRequestURI(), Map.of()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> statusException(ResponseStatusException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return error(status, status.getReasonPhrase(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missingResource(NoResourceFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedMethod(HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedMediaType(HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Use application/json for request bodies", request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ApiError> unacceptableMediaType(HttpMediaTypeNotAcceptableException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_ACCEPTABLE, "Response is available as application/json", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        // Do not log exception messages: they can contain request values or SQL parameters.
        log.error("Unhandled request failure: {}", exception.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), message, request.getRequestURI(), Map.of()));
    }
}
