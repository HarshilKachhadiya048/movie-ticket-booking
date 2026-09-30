package com.harshil.movieticketbooking.seat.dto;

import com.harshil.movieticketbooking.seat.domain.SeatCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * Defines a screen's whole seat layout in one request.
 * <p>
 * Rows are described rather than enumerated - "row A has 12 premium seats" -
 * because that is how a real auditorium is laid out, and enumerating two
 * hundred individual seats through the API would be tedious and error-prone.
 * The service expands each row into individual {@code seats} rows numbered
 * from 1.
 *
 * @param rows one entry per row of the auditorium
 */
public record SeatLayoutRequest(
        @NotEmpty(message = "At least one row is required")
        @Valid List<SeatRowRequest> rows) {

    /**
     * @param rowLabel  one or two letters, for example {@code A} or {@code AA}.
     *                  Constrained because it becomes part of the customer
     *                  facing seat label and is stored in a 5-character column.
     * @param seatCount how many seats this row has, numbered 1..seatCount
     * @param category  the pricing tier for every seat in the row
     */
    public record SeatRowRequest(
            @NotNull(message = "rowLabel is required")
            @Pattern(regexp = "^[A-Z]{1,3}$", message = "rowLabel must be 1-3 uppercase letters") String rowLabel,

            @NotNull(message = "seatCount is required")
            @Positive(message = "seatCount must be positive")
            @Max(value = 100, message = "A row may not exceed 100 seats") Integer seatCount,

            @NotNull(message = "category is required") SeatCategory category) {
    }
}
