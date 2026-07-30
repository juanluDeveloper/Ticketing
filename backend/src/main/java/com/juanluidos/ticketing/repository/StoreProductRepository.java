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

    List<StoreProduct> findByComparableGroupIdIsNull();

    /**
     * Candidatos a entrar en un grupo comparable, por similitud de nombre.
     *
     * <p>Excluye los que ya están en el grupo y descarta los de dimensión
     * incompatible: detergente en dosis y detergente en litros no se comparan,
     * por muy parecidos que suenen los nombres.
     */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT sp.id AS storeProductId,
                   similarity(upper(coalesce(sp.display_name, sp.canonical_name)), :name) AS similarity
            FROM store_product sp
            WHERE (sp.comparable_group_id IS NULL OR sp.comparable_group_id <> :groupId)
              AND (sp.dimension IS NULL OR sp.dimension = :dimension)
              AND similarity(upper(coalesce(sp.display_name, sp.canonical_name)), :name) >= :threshold
            ORDER BY similarity DESC
            LIMIT :maxResults
            """, nativeQuery = true)
    List<SimilarProduct> findCandidatesForGroup(
            @org.springframework.data.repository.query.Param("groupId") Long groupId,
            @org.springframework.data.repository.query.Param("name") String name,
            @org.springframework.data.repository.query.Param("dimension") String dimension,
            @org.springframework.data.repository.query.Param("threshold") double threshold,
            @org.springframework.data.repository.query.Param("maxResults") int maxResults);

    interface SimilarProduct {
        Long getStoreProductId();

        Double getSimilarity();
    }
}
