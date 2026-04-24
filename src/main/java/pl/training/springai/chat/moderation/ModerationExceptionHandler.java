package pl.training.springai.chat.moderation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Handler ModerationException - zwraca HTTP 400 z ProblemDetail (RFC 9457).
 */
@RestControllerAdvice
public class ModerationExceptionHandler {

    @ExceptionHandler(ModerationException.class)
    public ProblemDetail handleModerationException(ModerationException ex) {
        var problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setTitle("Moderation Exception");
        problemDetail.setProperty("category", ex.getCategory());
        return problemDetail;
    }
}
