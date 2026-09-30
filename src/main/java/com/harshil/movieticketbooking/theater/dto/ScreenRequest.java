package com.harshil.movieticketbooking.theater.dto;

import com.harshil.movieticketbooking.theater.domain.ScreenType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ScreenRequest(
        @NotNull(message = "theaterId is required") UUID theaterId,

        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must not exceed 100 characters") String name,

        @NotNull(message = "screenType is required") ScreenType screenType,

        Boolean active) {

    public boolean activeOrDefault() {
        return active == null || active;
    }
}
