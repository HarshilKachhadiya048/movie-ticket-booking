package com.harshil.movieticketbooking.theater.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record TheaterRequest(
        @NotNull(message = "cityId is required") UUID cityId,

        @NotBlank(message = "name is required")
        @Size(max = 150, message = "name must not exceed 150 characters") String name,

        @NotBlank(message = "address is required")
        @Size(max = 300, message = "address must not exceed 300 characters") String address,

        Boolean active) {

    public boolean activeOrDefault() {
        return active == null || active;
    }
}
