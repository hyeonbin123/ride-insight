package com.rideinsight.api

import com.rideinsight.fit.FitDecodeException
import com.rideinsight.service.InvalidActivityException
import com.rideinsight.service.NotFoundException
import com.rideinsight.service.UnsupportedSportException
import com.rideinsight.storage.DuplicateActivityException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun respond(status: HttpStatus, error: String, message: String) =
        ResponseEntity.status(status).body(ErrorResponse(error, message))

    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException) = respond(HttpStatus.NOT_FOUND, "not_found", e.message ?: "not found")

    @ExceptionHandler(FitDecodeException::class)
    fun fit(e: FitDecodeException) = respond(HttpStatus.BAD_REQUEST, "invalid_fit", "cannot decode FIT file: ${e.message}")

    @ExceptionHandler(InvalidActivityException::class)
    fun invalidActivity(e: InvalidActivityException) = respond(HttpStatus.BAD_REQUEST, "invalid_activity", e.message ?: "invalid")

    @ExceptionHandler(UnsupportedSportException::class)
    fun sport(e: UnsupportedSportException) = respond(HttpStatus.UNPROCESSABLE_CONTENT, "unsupported_sport", e.message ?: "")

    @ExceptionHandler(DuplicateActivityException::class)
    fun duplicate(e: DuplicateActivityException) = respond(HttpStatus.CONFLICT, "duplicate_activity", e.message ?: "")

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun tooLarge(e: MaxUploadSizeExceededException) =
        respond(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "the upload exceeds the 10 MB limit")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return respond(HttpStatus.BAD_REQUEST, "validation", details.ifEmpty { "invalid request body" })
    }

    @ExceptionHandler(
        IllegalArgumentException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestPartException::class,
    )
    fun badRequest(e: Exception) = respond(HttpStatus.BAD_REQUEST, "bad_request", e.message?.lineSequence()?.first() ?: "bad request")

    // Routing and protocol errors are 4xx, not "unexpected" (OWASP ZAP scan 1, 2026-10-10: every
    // unknown path answered 500 because the catch-all below swallowed NoResourceFoundException).
    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun noRoute(e: Exception) = respond(HttpStatus.NOT_FOUND, "not_found", "no such endpoint")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(e: HttpRequestMethodNotSupportedException) =
        respond(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "method ${e.method} is not supported here")

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class, HttpMediaTypeNotAcceptableException::class)
    fun mediaType(e: Exception) = respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "unsupported content type")

    @ExceptionHandler(MissingServletRequestParameterException::class, MultipartException::class)
    fun missing(e: Exception) = respond(HttpStatus.BAD_REQUEST, "bad_request", e.message?.lineSequence()?.first() ?: "bad request")

    @ExceptionHandler(Exception::class)
    fun other(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("unhandled error", e)
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "unexpected error")
    }
}
