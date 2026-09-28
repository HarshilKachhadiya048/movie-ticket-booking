package com.harshil.movieticketbooking;

import org.springframework.boot.SpringApplication;

public class TestMovieTicketBookingApplication {

    public static void main(String[] args) {
        SpringApplication.from(MovieTicketBookingApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
