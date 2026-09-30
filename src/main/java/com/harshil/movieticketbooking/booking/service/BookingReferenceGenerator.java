package com.harshil.movieticketbooking.booking.service;

import com.harshil.movieticketbooking.booking.config.BookingProperties;
import java.security.SecureRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Produces the short reference a customer quotes at the counter, for example
 * {@code MTB-7K4QX9DHF2}.
 * <p>
 * The booking's UUID is the real identifier; this is the human-facing one.
 * The alphabet omits {@code 0/O} and {@code 1/I/L} so a reference read aloud
 * or copied off a screen cannot be transcribed wrongly.
 * <p>
 * Twelve characters from a 32-symbol alphabet is roughly 2^60 of space, so
 * collisions are not a practical concern - and the unique constraint on
 * {@code booking_reference} is there if one ever happened.
 */
@Component
@RequiredArgsConstructor
public class BookingReferenceGenerator {

    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final int LENGTH = 12;

    private final SecureRandom random = new SecureRandom();
    private final BookingProperties bookingProperties;

    public String generate() {
        StringBuilder reference = new StringBuilder(bookingProperties.referencePrefix().length() + 1 + LENGTH);
        reference.append(bookingProperties.referencePrefix()).append('-');
        for (int i = 0; i < LENGTH; i++) {
            reference.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return reference.toString();
    }
}
