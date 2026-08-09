package com.juanluidos.ticketing.repository;

import com.juanluidos.ticketing.domain.DeclaredPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DeclaredPriceRepository extends JpaRepository<DeclaredPrice, Long> {

    List<DeclaredPrice> findByStoreProductIdOrderByDeclaredAtAsc(Long storeProductId);

    /** La vigente: la última lectura del cartel. */
    Optional<DeclaredPrice> findFirstByStoreProductIdOrderByDeclaredAtDesc(Long storeProductId);

    /** Dos lecturas del mismo día son la misma: la segunda corrige a la primera. */
    Optional<DeclaredPrice> findByStoreProductIdAndDeclaredAt(Long storeProductId, LocalDate declaredAt);
}
