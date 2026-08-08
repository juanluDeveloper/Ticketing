package com.juanluidos.ticketing.api;

import com.juanluidos.ticketing.history.PriceHistory;
import com.juanluidos.ticketing.history.ProductHistoryService;
import com.juanluidos.ticketing.repository.StoreProductRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductHistoryController {

    private final ProductHistoryService history;
    private final StoreProductRepository products;

    public ProductHistoryController(ProductHistoryService history, StoreProductRepository products) {
        this.history = history;
        this.products = products;
    }

    /** Cuántos puntos lleva la miniserie de la lista. Doce compras ya dibujan una tendencia. */
    private static final int SPARKLINE_POINTS = 12;

    /** Fila de la lista de productos, con lo justo para decidir en cuál entrar. */
    public record ProductSummary(
            Long id,
            String storeCode,
            String storeName,
            String name,
            int purchaseCount,
            BigDecimal lastNormalizedUnitPrice,
            String normalizedUnit,
            Integer daysSinceLast,
            int increaseCount,
            boolean comparable,
            /**
             * El motivo también en la lista, no solo en la ficha. Casi siempre es
             * "falta el tamaño del envase", que se arregla en diez segundos si
             * alguien te lo dice antes de entrar.
             */
            String notComparableReason,
            /** Últimos precios normalizados, en orden cronológico, para la miniserie. */
            List<BigDecimal> series,
            /** Variación entre el primer y el último punto de esa miniserie, en %. */
            BigDecimal changePct
    ) {
    }

    @GetMapping
    public List<ProductSummary> list(@RequestParam(required = false) Long storeId) {
        var all = storeId == null ? products.findAll() : products.findByStoreId(storeId);
        return all.stream()
                .map(p -> {
                    // El histórico completo ya se calcula aquí desde siempre; la
                    // serie y la variación salen de él sin una consulta más.
                    PriceHistory h = history.of(p.getId());
                    List<BigDecimal> series = sparkline(h);
                    return new ProductSummary(
                            p.getId(),
                            h.product().storeCode(),
                            h.product().storeName(),
                            p.getDisplayName() == null ? p.getCanonicalName() : p.getDisplayName(),
                            h.metrics().purchaseCount(),
                            h.metrics().lastNormalizedUnitPrice(),
                            h.metrics().normalizedUnit(),
                            h.metrics().daysSinceLast(),
                            h.metrics().increaseCount(),
                            h.notComparableReason() == null,
                            h.notComparableReason(),
                            series,
                            changePct(series));
                })
                // Primero lo más comprado: es donde el histórico tiene algo que contar.
                .sorted(Comparator.comparingInt(ProductSummary::purchaseCount).reversed()
                        .thenComparing(ProductSummary::name))
                .toList();
    }

    @GetMapping("/{id}/history")
    public PriceHistory history(@PathVariable Long id) {
        return history.of(id);
    }

    /**
     * Solo los puntos normalizados: mezclar €/pieza con €/kg en la misma línea
     * dibujaría un escalón que no ha ocurrido.
     */
    private List<BigDecimal> sparkline(PriceHistory h) {
        List<BigDecimal> normalized = h.points().stream()
                .map(PriceHistory.Point::normalizedUnitPrice)
                .filter(java.util.Objects::nonNull)
                .toList();
        return normalized.size() <= SPARKLINE_POINTS
                ? normalized
                : normalized.subList(normalized.size() - SPARKLINE_POINTS, normalized.size());
    }

    /**
     * De punta a punta de la miniserie, que es el tramo que se ve dibujado. Con
     * un solo punto no hay variación que contar y devuelve null en vez de cero:
     * un cero diría "no ha cambiado", y lo cierto es que no se sabe.
     */
    private BigDecimal changePct(List<BigDecimal> series) {
        if (series.size() < 2) {
            return null;
        }
        BigDecimal first = series.get(0);
        if (first.signum() == 0) {
            return null;
        }
        return series.get(series.size() - 1).subtract(first)
                .multiply(BigDecimal.valueOf(100))
                .divide(first, 1, java.math.RoundingMode.HALF_UP);
    }
}
