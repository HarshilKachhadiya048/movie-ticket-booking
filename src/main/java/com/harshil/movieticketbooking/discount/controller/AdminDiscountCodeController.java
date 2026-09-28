package com.harshil.movieticketbooking.discount.controller;

import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.discount.dto.DiscountCodeRequest;
import com.harshil.movieticketbooking.discount.dto.DiscountCodeResponse;
import com.harshil.movieticketbooking.discount.service.DiscountCodeAdminService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AdminDiscountCodeController {

    private final DiscountCodeAdminService discountCodeAdminService;

    @GetMapping(ApiEndpoints.Admin.DiscountCodes.ROOT)
    public List<DiscountCodeResponse> listCodes() {
        return discountCodeAdminService.listCodes();
    }

    @GetMapping(ApiEndpoints.Admin.DiscountCodes.BY_ID)
    public DiscountCodeResponse getCode(@PathVariable UUID discountCodeId) {
        return discountCodeAdminService.getCode(discountCodeId);
    }

    @PostMapping(ApiEndpoints.Admin.DiscountCodes.ROOT)
    @ResponseStatus(HttpStatus.CREATED)
    public DiscountCodeResponse createCode(@Valid @RequestBody DiscountCodeRequest request) {
        return discountCodeAdminService.createCode(request);
    }

    /** The code string itself is immutable; every limit around it is editable. */
    @PutMapping(ApiEndpoints.Admin.DiscountCodes.BY_ID)
    public DiscountCodeResponse updateCode(
            @PathVariable UUID discountCodeId,
            @Valid @RequestBody DiscountCodeRequest request) {
        return discountCodeAdminService.updateCode(discountCodeId, request);
    }

    @DeleteMapping(ApiEndpoints.Admin.DiscountCodes.BY_ID)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateCode(@PathVariable UUID discountCodeId) {
        discountCodeAdminService.deactivateCode(discountCodeId);
    }
}
