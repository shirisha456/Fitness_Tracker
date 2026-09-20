package com.fitnesstracker.common.exception;

import com.fitnesstracker.common.api.ErrorCode;
import com.fitnesstracker.common.api.ErrorResponse;
import com.fitnesstracker.common.api.ErrorResponse.ErrorDetail;
import com.fitnesstracker.common.web.CorrelationIdFilter;
import com.fitnesstracker.ai.provider.AiProviderException;
import com.fitnesstracker.security.TokenException;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every error as the envelope the existing frontend expects.
 *
 * <p>Two behaviours are deliberate and differ from Spring's defaults:
 *
 * <ul>
 *   <li><b>Validation failures return 400, not 422.</b> the previous implementation's default is 422 and the
 *       previous implementation overrides it to 400; the frontend was written against 400.
 *   <li><b>Unhandled exceptions never leak.</b> The stack trace is logged with the
 *       correlation id; the client receives a fixed message and that id, so an incident
 *       can still be traced without exposing internals.
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleApp(AppException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(
                        ex.getCode(),
                        ex.getMessage(),
                        ex.getDetails(),
                        CorrelationIdFilter.current(request)));
    }

    /**
     * A bad token presented in a request <em>body</em> — {@code /auth/refresh} and
     * {@code /auth/logout} — rather than in the Authorization header.
     *
     * <p>Header tokens are handled by the security filter and rendered by
     * {@link com.fitnesstracker.security.RestAuthenticationEntryPoint}; these never reach
     * it, because the endpoints are anonymous. Without this handler they escape as 500,
     * and the BFF's silent refresh depends on seeing a 401.
     */
    /**
     * Upstream AI failures.
     *
     * <p>503 when the provider is unconfigured, misconfigured or rate-limited; 502 when it
     * answered with something unusable. Core fitness functionality never routes through
     * here — only the {@code /ai/**} endpoints can fail this way, which is what "graceful
     * degradation" means concretely: the AI coach goes dark, the app does not.
     */
    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<ErrorResponse> handleAiProvider(
            AiProviderException ex, HttpServletRequest request) {
        boolean unavailable = ex.getKind() == AiProviderException.Kind.UNAVAILABLE;
        HttpStatus status = unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        String code = unavailable ? ErrorCode.SERVICE_UNAVAILABLE : ErrorCode.BAD_GATEWAY;
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(code, ex.getMessage(), List.of(),
                        CorrelationIdFilter.current(request)));
    }

    /**
     * The database was reachable but no connection could be obtained in time — a saturated
     * pool, not a broken request and not a bug.
     *
     * <p>Without this it reaches the catch-all and becomes a 500. Under the Phase 13 stress
     * run that produced 648 HTTP 500s on {@code /auth/login}, which reads as "the service is
     * broken" when the truth is "the service is at capacity". 503 is the honest answer and
     * is the one a load balancer or client retry policy knows how to act on.
     *
     * <p>The cause is logged; the client is told nothing about pools, drivers or SQL.
     */
    @ExceptionHandler({DataAccessResourceFailureException.class,
                       CannotCreateTransactionException.class})
    public ResponseEntity<ErrorResponse> handleNoConnectionAvailable(
            Exception ex, HttpServletRequest request) {
        log.warn("database_connection_unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of(ErrorCode.SERVICE_UNAVAILABLE,
                        "Service temporarily unavailable", List.of(),
                        CorrelationIdFilter.current(request)));
    }

    @ExceptionHandler(TokenException.class)
    public ResponseEntity<ErrorResponse> handleToken(
            TokenException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(
                        ex.getCode(), ex.getMessage(), List.of(),
                        CorrelationIdFilter.current(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ErrorDetail> details = new ArrayList<>();
        for (var error : ex.getBindingResult().getFieldErrors()) {
            details.add(new ErrorDetail(
                    error.getField(), error.getDefaultMessage(), "INVALID_FORMAT"));
        }
        for (var error : ex.getBindingResult().getGlobalErrors()) {
            // Cross-field rules (password_confirm matching, for instance) have no single
            // field; the contract reports these with an empty field name.
            details.add(new ErrorDetail("", error.getDefaultMessage(), "INVALID_FORMAT"));
        }
        return validationFailure(details, request);
    }

    /**
     * Constraints declared directly on controller method parameters — {@code @Min},
     * {@code @Max} on a {@code @RequestParam} in an {@code @Validated} controller.
     *
     * <p>These take a different path from body validation and arrive as a
     * {@code ConstraintViolationException}; without this they escape as a 500, which is
     * how an out-of-range {@code ?limit=} was answering.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolations(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<ErrorDetail> details = ex.getConstraintViolations().stream()
                .map(violation -> new ErrorDetail(
                        lastNode(violation.getPropertyPath().toString()),
                        violation.getMessage(),
                        "INVALID_FORMAT"))
                .toList();
        return validationFailure(details, request);
    }

    /** "history.limit" -> "limit": the client named the parameter, not the method. */
    private static String lastNode(String propertyPath) {
        int lastDot = propertyPath.lastIndexOf('.');
        return lastDot < 0 ? propertyPath : propertyPath.substring(lastDot + 1);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleParameterValidation(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        List<ErrorDetail> details = new ArrayList<>();
        ex.getAllValidationResults().forEach(result ->
                result.getResolvableErrors().forEach(error -> details.add(new ErrorDetail(
                        result.getMethodParameter().getParameterName(),
                        error.getDefaultMessage(),
                        "INVALID_FORMAT"))));
        return validationFailure(details, request);
    }

    /** A malformed or absent JSON body is a client error, not a server one. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpServletRequest request) {
        return validationFailure(
                List.of(new ErrorDetail("", "Request body is missing or malformed",
                        "INVALID_FORMAT")),
                request);
    }

    /** e.g. a path variable that is not a valid UUID. */
    /**
     * A required query parameter was not sent at all.
     *
     * <p>Without this, Spring lets {@code MissingServletRequestParameterException} reach the
     * catch-all and the client gets a 500 for what is plainly a bad request. the previous implementation answers
     * 400 with {@code query.<name>} / "Field required", so that shape is reproduced exactly —
     * including the {@code query.} prefix, which is the previous implementation's location marker and is how the
     * existing clients read it.
     *
     * <p>Found by the Phase 13 load test: {@code GET /nutrition/summary} with no {@code date}
     * returned 8,720 HTTP 500s. The response-parity suite never caught it because every
     * comparison sent a well-formed request — a gap in the tests as much as in the code.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return validationFailure(
                List.of(new ErrorDetail(
                        "query." + ex.getParameterName(), "Field required", "INVALID_FORMAT")),
                request);
    }

    /**
     * A parameter was present but could not be converted — {@code ?date=not-a-date},
     * {@code /workouts/not-a-uuid}.
     *
     * <p>The field name mirrors the previous implementation's: a location prefix ({@code query.} or
     * {@code path.}) plus the parameter name in snake_case, because that is the name the
     * client sent. Java's parameter is {@code workoutId}; the client wrote
     * {@code workout_id}, and telling it about a name it never used is unhelpful.
     *
     * <p>The message text is deliberately NOT matched to Pydantic's ("Input should be a
     * valid UUID, invalid character: found `n` at 1"). Reproducing that prose would mean
     * reimplementing Pydantic's error strings for every type; the machine-readable parts —
     * status, {@code error.code} and {@code details[].code} — do match, and those are what
     * clients branch on. Recorded in docs/api-compatibility.md.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return validationFailure(
                List.of(new ErrorDetail(
                        parameterLocation(ex) + snakeCase(ex.getName()),
                        "Invalid value", "INVALID_FORMAT")),
                request);
    }

    /** "query." or "path.", matching the previous implementation's error location marker. */
    private static String parameterLocation(MethodArgumentTypeMismatchException ex) {
        MethodParameter parameter = ex.getParameter();
        return parameter != null && parameter.hasParameterAnnotation(PathVariable.class)
                ? "path." : "query.";
    }

    /** "workoutId" -> "workout_id": report the name the client actually sent. */
    static String snakeCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        StringBuilder out = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(
                        ErrorCode.NOT_FOUND, "Not Found", List.of(),
                        CorrelationIdFilter.current(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);
        // The only place a stack trace is emitted. Never returned to the client.
        log.error("Unhandled exception correlation_id={}", correlationId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(
                        ErrorCode.INTERNAL_ERROR,
                        "An unexpected error occurred",
                        List.of(),
                        correlationId));
    }

    private ResponseEntity<ErrorResponse> validationFailure(
            List<ErrorDetail> details, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(
                        ErrorCode.VALIDATION_ERROR,
                        "Request validation failed",
                        details,
                        CorrelationIdFilter.current(request)));
    }
}
