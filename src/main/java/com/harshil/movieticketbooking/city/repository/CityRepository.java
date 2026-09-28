package com.harshil.movieticketbooking.city.repository;

import com.harshil.movieticketbooking.city.domain.City;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CityRepository extends JpaRepository<City, UUID> {

    List<City> findAllByActiveTrueOrderByNameAsc();

    boolean existsByNameIgnoreCaseAndStateIgnoreCase(String name, String state);

    boolean existsByNameIgnoreCaseAndStateIgnoreCaseAndIdNot(String name, String state, UUID id);
}
