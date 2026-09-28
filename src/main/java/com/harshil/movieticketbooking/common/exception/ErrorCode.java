package com.harshil.movieticketbooking.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Every failure the API can report, paired with the HTTP status it maps to.
 * <p>
 * Keeping the status on the error code rather than on an exception hierarchy
 * means there is exactly one place to look up "what does the client see", and
 * services throw a single {@link DomainException} type.
 * <p>
 * Status conventions used consistently across the API:
 * <ul>
 *     <li><b>404</b> - the addressed resource does not exist.</li>
 *     <li><b>400</b> - the request itself is malformed or fails bean validation.</li>
 *     <li><b>401 / 403</b> - not authenticated / not allowed to touch this resource.</li>
 *     <li><b>409</b> - a conflict over shared mutable state: seat availability,
 *     concurrency, booking lifecycle, duplicate resources.</li>
 *     <li><b>422</b> - the request is well formed but violates a configured
 *     business rule, such as a discount code's own validity limits.</li>
 *     <li><b>402</b> - the payment was declined by the gateway.</li>
 *     <li><b>502</b> - the payment gateway itself failed.</li>
 * </ul>
 */
public enum ErrorCode {

    // --- Not found ---------------------------------------------------------
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    CITY_NOT_FOUND(HttpStatus.NOT_FOUND, "City not found"),
    MOVIE_NOT_FOUND(HttpStatus.NOT_FOUND, "Movie not found"),
    THEATER_NOT_FOUND(HttpStatus.NOT_FOUND, "Theater not found"),
    SCREEN_NOT_FOUND(HttpStatus.NOT_FOUND, "Screen not found"),
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "Seat not found"),
    SHOW_NOT_FOUND(HttpStatus.NOT_FOUND, "Show not found"),
    BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "Booking not found"),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment not found"),
    DISCOUNT_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "Discount code not found"),
    PRICING_RULE_NOT_FOUND(HttpStatus.NOT_FOUND, "No pricing rule matches this seat"),
    REFUND_POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "Refund policy not found"),

    // --- Duplicate resources (409) -----------------------------------------
    USERNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "Username is already taken"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email is already registered"),
    CITY_ALREADY_EXISTS(HttpStatus.CONFLICT, "A city with this name already exists in that state"),
    MOVIE_ALREADY_EXISTS(HttpStatus.CONFLICT, "A movie with this title and language already exists"),
    THEATER_ALREADY_EXISTS(HttpStatus.CONFLICT, "A theater with this name already exists in that city"),
    SCREEN_ALREADY_EXISTS(HttpStatus.CONFLICT, "A screen with this name already exists in that theater"),
    SEAT_ALREADY_EXISTS(HttpStatus.CONFLICT, "A seat with this row and number already exists on that screen"),
    SHOW_ALREADY_EXISTS(HttpStatus.CONFLICT, "A show already starts at that time on this screen"),
    SHOW_OVERLAPS_EXISTING(HttpStatus.CONFLICT, "The show overlaps another show on the same screen"),
    PRICING_RULE_ALREADY_EXISTS(HttpStatus.CONFLICT, "A pricing rule already exists for that scope"),
    DISCOUNT_CODE_ALREADY_EXISTS(HttpStatus.CONFLICT, "A discount code with this code already exists"),
    REFUND_POLICY_ALREADY_EXISTS(HttpStatus.CONFLICT, "A refund policy with this name already exists"),
    REFUND_POLICY_BANDS_OVERLAP(HttpStatus.CONFLICT, "Refund policy hour bands must not overlap"),

    // --- Seat allocation conflicts (409) -----------------------------------
    SEAT_ALREADY_HELD(HttpStatus.CONFLICT, "One or more seats are currently held by another customer"),
    SEAT_ALREADY_BOOKED(HttpStatus.CONFLICT, "One or more seats are already booked"),
    SEAT_NOT_IN_SHOW(HttpStatus.CONFLICT, "One or more seats do not belong to this show"),
    HOLD_EXPIRED(HttpStatus.CONFLICT, "The seat hold has expired"),
    INVALID_HOLD(HttpStatus.CONFLICT, "The seat hold is no longer valid"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "The resource was modified concurrently, please retry"),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "The request conflicts with existing data"),

    // --- Show / booking lifecycle (409) ------------------------------------
    SHOW_NOT_BOOKABLE(HttpStatus.CONFLICT, "This show is not open for booking"),
    SHOW_ALREADY_STARTED(HttpStatus.CONFLICT, "This show has already started"),
    BOOKING_NOT_CANCELLABLE(HttpStatus.CONFLICT, "This booking can no longer be cancelled"),
    INVALID_BOOKING_STATE_TRANSITION(HttpStatus.CONFLICT, "The booking is not in a state that allows this operation"),
    PAYMENT_ALREADY_PROCESSED(HttpStatus.CONFLICT, "A payment has already been processed for this booking"),

    // --- Request validation (400) ------------------------------------------
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "The request body could not be parsed"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "The request is invalid"),
    DUPLICATE_SEAT_IN_REQUEST(HttpStatus.BAD_REQUEST, "The same seat was requested more than once"),
    TOO_MANY_SEATS(HttpStatus.BAD_REQUEST, "Too many seats requested in a single booking"),

    // --- Business rule violations (422) ------------------------------------
    DISCOUNT_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "The discount code is not valid"),
    DISCOUNT_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "The discount code is not active"),
    DISCOUNT_EXPIRED(HttpStatus.UNPROCESSABLE_ENTITY, "The discount code has expired or is not yet valid"),
    DISCOUNT_MIN_AMOUNT_NOT_MET(HttpStatus.UNPROCESSABLE_ENTITY, "The booking total is below the discount code minimum"),
    DISCOUNT_USAGE_LIMIT_REACHED(HttpStatus.UNPROCESSABLE_ENTITY, "The discount code has reached its usage limit"),
    DISCOUNT_USER_LIMIT_REACHED(HttpStatus.UNPROCESSABLE_ENTITY, "You have already used this discount code the maximum number of times"),
    REFUND_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "No refund is available for this cancellation"),
    SEAT_LAYOUT_NOT_EMPTY(HttpStatus.UNPROCESSABLE_ENTITY, "The screen already has a seat layout"),
    SCREEN_HAS_NO_SEATS(HttpStatus.UNPROCESSABLE_ENTITY, "The screen has no active seats"),

    // --- Authentication and authorization ----------------------------------
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You are not allowed to perform this operation"),
    UNAUTHORIZED_BOOKING_ACCESS(HttpStatus.FORBIDDEN, "This booking belongs to another customer"),

    // --- Payment -----------------------------------------------------------
    PAYMENT_FAILED(HttpStatus.PAYMENT_REQUIRED, "The payment was declined"),
    PAYMENT_GATEWAY_ERROR(HttpStatus.BAD_GATEWAY, "The payment gateway could not be reached"),

    // --- Fallback ----------------------------------------------------------
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
