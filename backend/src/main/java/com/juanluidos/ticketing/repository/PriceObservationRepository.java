package com.juanluidos.ticketing.repository;

import com.juanluidos.ticketing.domain.PriceObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriceObservationRepository extends JpaRepository<PriceObservation, Long> {

    List<PriceObservation> findByStoreProductIdOrderByObservedAtAsc(Long storeProductId);

    /** Serie limpia para el conteo de subidas: sin promociones ni piezas de peso variable. */
    List<PriceObservation> findByStoreProductIdAndCountsForIncreaseTrueOrderByObservedAtAsc(
            Long storeProductId);

    void deleteByLineItemId(Long lineItemId);
}
