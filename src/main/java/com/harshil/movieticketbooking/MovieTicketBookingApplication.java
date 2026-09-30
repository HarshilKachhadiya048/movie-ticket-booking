package com.harshil.movieticketbooking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Movie ticket booking platform.
 * <p>
 * A modular monolith organised by business domain: {@code common},
 * {@code user}, {@code security}, {@code city}, {@code movie},
 * {@code theater}, {@code screen}, {@code seat}, {@code show},
 * {@code pricing}, {@code discount}, {@code booking}, {@code payment},
 * {@code refund} and {@code notification}. Each package owns its entities,
 * repositories, services, DTOs and controllers; cross-domain calls go through
 * the owning service, never through another domain's repository.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MovieTicketBookingApplication {

    public static void main(String[] args) {
        SpringApplication.run(MovieTicketBookingApplication.class, args);
    }
}
