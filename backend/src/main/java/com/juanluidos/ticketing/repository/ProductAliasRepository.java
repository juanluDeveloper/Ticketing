package com.juanluidos.ticketing.repository;

import com.juanluidos.ticketing.domain.ProductAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductAliasRepository extends JpaRepository<ProductAlias, Long> {

    /**
     * Pasada 1 del matcher: alias normalizado idéntico dentro del mismo súper.
     * Resuelve la mayoría de líneas, porque los tickets repiten la cadena tal cual.
     */
    Optional<ProductAlias> findByStoreIdAndNormalizedText(Long storeId, String normalizedText);

    /**
     * Pasada 2: similitud de trigramas, solo para lo que no casó exacto.
     *
     * <p>Se compara contra {@code latin_text} cuando existe y contra
     * {@code normalized_text} si no: pg_trgm rinde mal sobre chino, así que para
     * las descripciones de Xinya la similitud se calcula sobre la cola en
     * alfabeto latino.
     */
    @Query(value = """
            SELECT a.id AS aliasId,
                   a.store_product_id AS storeProductId,
                   a.raw_text AS rawText,
                   similarity(COALESCE(a.latin_text, a.normalized_text), :candidate) AS similarity
            FROM product_alias a
            WHERE a.store_id = :storeId
              AND similarity(COALESCE(a.latin_text, a.normalized_text), :candidate) >= :threshold
            ORDER BY similarity DESC
            LIMIT :maxResults
            """, nativeQuery = true)
    List<SimilarAlias> findSimilar(@Param("storeId") Long storeId,
                                   @Param("candidate") String candidate,
                                   @Param("threshold") double threshold,
                                   @Param("maxResults") int maxResults);

    /** Proyección con la similitud, que es lo que se guarda como match_confidence. */
    interface SimilarAlias {
        Long getAliasId();

        Long getStoreProductId();

        String getRawText();

        Double getSimilarity();
    }

    List<ProductAlias> findByStoreProductId(Long storeProductId);
}
