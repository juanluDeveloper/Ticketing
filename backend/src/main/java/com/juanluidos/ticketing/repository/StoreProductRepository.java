package com.juanluidos.ticketing.repository;

import com.juanluidos.ticketing.domain.StoreProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoreProductRepository extends JpaRepository<StoreProduct, Long> {

    Optional<StoreProduct> findByStoreIdAndCanonicalName(Long storeId, String canonicalName);

    List<StoreProduct> findByStoreId(Long storeId);

    List<StoreProduct> findByComparableGroupId(Long comparableGroupId);

    /** Productos aún sin tamaño de envase: sus precios no se pueden normalizar. */
    List<StoreProduct> findByPackageSizeIsNullAndSoldByNot(
            com.juanluidos.ticketing.domain.SoldBy soldBy);
}
