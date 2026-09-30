package com.harshil.movieticketbooking.discount.service;

import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.common.money.Money;
import com.harshil.movieticketbooking.discount.domain.DiscountCode;
import com.harshil.movieticketbooking.discount.domain.DiscountType;
import com.harshil.movieticketbooking.discount.dto.DiscountCodeRequest;
import com.harshil.movieticketbooking.discount.dto.DiscountCodeResponse;
import com.harshil.movieticketbooking.discount.repository.DiscountCodeRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration of discount codes.
 * <p>
 * Kept separate from {@link DiscountService}, which is the redemption path.
 * They have different callers, different transaction requirements - redemption
 * runs under a row lock inside the booking transaction - and different reasons
 * to change.
 * <p>
 * Codes are stored upper-cased and matched case-insensitively, so a customer
 * typing {@code welcome50} redeems {@code WELCOME50}.
 */
@Service
@RequiredArgsConstructor
public class DiscountCodeAdminService {

    private final DiscountCodeRepository discountCodeRepository;

    @Transactional(readOnly = true)
    public List<DiscountCodeResponse> listCodes() {
        return discountCodeRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(DiscountCode::getCode))
                .map(DiscountCodeResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DiscountCodeResponse getCode(UUID discountCodeId) {
        return DiscountCodeResponse.from(requireCode(discountCodeId));
    }

    @Transactional
    public DiscountCodeResponse createCode(DiscountCodeRequest request) {
        validate(request);
        String code = normalize(request.code());
        if (discountCodeRepository.existsByCodeIgnoreCase(code)) {
            throw new DomainException(
                    ErrorCode.DISCOUNT_CODE_ALREADY_EXISTS, "Discount code '%s' already exists".formatted(code));
        }

        DiscountCode discountCode = discountCodeRepository.save(DiscountCode.builder()
                .code(code)
                .description(request.description())
                .discountType(request.discountType())
                .discountValue(Money.normalize(request.discountValue()))
                .maxDiscountAmount(request.maxDiscountAmount() == null
                        ? null
                        : Money.normalize(request.maxDiscountAmount()))
                .minBookingAmount(Money.normalize(request.minBookingAmountOrZero()))
                .validFrom(request.validFrom())
                .validUntil(request.validUntil())
                .usageLimit(request.usageLimit())
                .perUserLimit(request.perUserLimit())
                .usedCount(0)
                .active(request.activeOrDefault())
                .build());
        return DiscountCodeResponse.from(discountCode);
    }

    /**
     * The code string itself is immutable - customers may already be holding
     * it - but every limit around it can be adjusted.
     */
    @Transactional
    public DiscountCodeResponse updateCode(UUID discountCodeId, DiscountCodeRequest request) {
        validate(request);
        DiscountCode discountCode = requireCode(discountCodeId);

        discountCode.update(
                request.description(),
                request.discountType(),
                Money.normalize(request.discountValue()),
                request.maxDiscountAmount() == null ? null : Money.normalize(request.maxDiscountAmount()),
                Money.normalize(request.minBookingAmountOrZero()),
                request.validFrom(),
                request.validUntil(),
                request.usageLimit(),
                request.perUserLimit(),
                request.activeOrDefault());
        return DiscountCodeResponse.from(discountCode);
    }

    /**
     * Deactivates rather than deletes: {@code discount_code_usages} and
     * {@code bookings} both reference the code, and a past booking must keep
     * showing which code it used.
     */
    @Transactional
    public void deactivateCode(UUID discountCodeId) {
        DiscountCode discountCode = requireCode(discountCodeId);
        discountCode.update(
                discountCode.getDescription(),
                discountCode.getDiscountType(),
                discountCode.getDiscountValue(),
                discountCode.getMaxDiscountAmount(),
                discountCode.getMinBookingAmount(),
                discountCode.getValidFrom(),
                discountCode.getValidUntil(),
                discountCode.getUsageLimit(),
                discountCode.getPerUserLimit(),
                false);
    }

    private DiscountCode requireCode(UUID discountCodeId) {
        return discountCodeRepository.findById(discountCodeId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.DISCOUNT_CODE_NOT_FOUND,
                        "Discount code %s does not exist".formatted(discountCodeId)));
    }

    private static String normalize(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    /** Cross-field rules that a single-field annotation cannot express. */
    private static void validate(DiscountCodeRequest request) {
        if (!request.validUntil().isAfter(request.validFrom())) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "validUntil must be after validFrom");
        }
        if (request.discountType() == DiscountType.PERCENTAGE
                && request.discountValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new DomainException(
                    ErrorCode.INVALID_REQUEST, "A percentage discount cannot exceed 100");
        }
    }
}
