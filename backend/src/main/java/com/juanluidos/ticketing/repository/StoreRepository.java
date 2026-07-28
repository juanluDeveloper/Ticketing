package com.juanluidos.ticketing.repository;

import com.juanluidos.ticketing.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    Optional<Store> findByCode(String code);

    /** Auto-detección del súper por el NIF/CIF de la cabecera. */
    Optional<Store> findByTaxId(String taxId);
}
