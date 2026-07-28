package com.juanluidos.ticketing.repository;

import com.juanluidos.ticketing.domain.StoreTaxLetter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoreTaxLetterRepository extends JpaRepository<StoreTaxLetter, Long> {

    List<StoreTaxLetter> findByStoreId(Long storeId);
}
