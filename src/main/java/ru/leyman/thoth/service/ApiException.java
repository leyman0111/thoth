package ru.leyman.thoth.service;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Бизнес-ошибка с HTTP-статусом. Наследуется от {@link ErrorResponseException},
 * поэтому Spring MVC сам отдаёт её как application/problem+json (RFC 9457):
 * {"status": 409, "title": "Conflict", "detail": "..."}.
 */
public class ApiException extends ErrorResponseException {

    private ApiException(HttpStatus status, String detail, Throwable cause) {
        super(status, ProblemDetail.forStatusAndDetail(status, detail), cause);
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail, null);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail, null);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail, null);
    }

    public static ApiException unsupportedMediaType(String detail) {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, detail, null);
    }

    public static ApiException unprocessable(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, detail, null);
    }

    public static ApiException badGateway(String detail, Throwable cause) {
        return new ApiException(HttpStatus.BAD_GATEWAY, detail, cause);
    }

    @Override
    public String getMessage() {
        return getBody().getDetail();
    }
}
