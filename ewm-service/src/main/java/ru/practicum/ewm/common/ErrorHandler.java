package ru.practicum.ewm.common;

import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.ErrorResponse;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class ErrorHandler {
    private final Clock clock;

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), "The required object was not found.");
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), "Conditions for the requested operation are not met.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integrity(DataIntegrityViolationException exception) {
        log.debug("Database constraint violation", exception);
        return error(HttpStatus.CONFLICT, "Duplicate data or a referenced object cannot be removed.",
                "Integrity constraint has been violated.");
    }

    @ExceptionHandler({BadRequestException.class, ConstraintViolationException.class, BindException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ApiError> badRequest(Exception exception) {
        String message = exception instanceof HttpMessageNotReadableException
                ? "Request body has invalid field values or format." : exception.getMessage();
        return error(HttpStatus.BAD_REQUEST, message, "Incorrectly made request.");
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ApiError> statisticsUnavailable(RestClientException exception) {
        log.warn("Statistics request failed: {}", exception.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Statistics service is unavailable.",
                "A required service could not complete the request.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception) {
        if (exception instanceof ErrorResponse response) {
            HttpStatus status = HttpStatus.valueOf(response.getStatusCode().value());
            return error(status, status.getReasonPhrase(), "Incorrectly made request.");
        }
        log.error("Request processing failed", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error.", "Unexpected error.");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message, String reason) {
        return ResponseEntity.status(status).body(new ApiError(List.of(), message, reason, status.name(),
                LocalDateTime.now(clock)));
    }
}
