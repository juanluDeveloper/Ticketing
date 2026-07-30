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

    /** Fila de la lista de productos, con lo justo para decidir en cuál entrar. */
    public record ProductSummary(
            Long id,
            String storeCode,
            String name,
            int purchaseCount,
            BigDecimal lastNormalizedUnitPrice,
            String normalizedUnit,
            Integer daysSinceLast,
            int increaseCount,
            boolean comparable
    ) {
    }

    @GetMapping
    public List<ProductSummary> list(@RequestParam(required = false) Long storeId) {
        var all = storeId == null ? products.findAll() : products.findByStoreId(storeId);
        return all.stream()
                .map(p -> {
                    PriceHistory h = history.of(p.getId());
                    return new ProductSummary(
                            p.getId(),
                            h.product().storeCode(),
                            p.getDisplayName() == null ? p.getCanonicalName() : p.getDisplayName(),
                            h.metrics().purchaseCount(),
                            h.metrics().lastNormalizedUnitPrice(),
                            h.metrics().normalizedUnit(),
                            h.metrics().daysSinceLast(),
                            h.metrics().increaseCount(),
                            h.notComparableReason() == null);
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
}
