package com.juanluidos.ticketing.api;

import com.juanluidos.ticketing.domain.StoreProduct;
import com.juanluidos.ticketing.matching.TextNormalizer;
import com.juanluidos.ticketing.repository.CategoryRepository;
import com.juanluidos.ticketing.repository.ProductAliasRepository;
import com.juanluidos.ticketing.repository.StoreProductRepository;
import com.juanluidos.ticketing.repository.StoreRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/** Lo que la pantalla de validación necesita para elegir producto y categoría. */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final StoreRepository stores;
    private final StoreProductRepository products;
    private final ProductAliasRepository aliases;
    private final CategoryRepository categories;

    public CatalogController(StoreRepository stores, StoreProductRepository products,
                             ProductAliasRepository aliases, CategoryRepository categories) {
        this.stores = stores;
        this.products = products;
        this.aliases = aliases;
        this.categories = categories;
    }

    @GetMapping("/stores")
    public List<TicketViews.StoreView> stores() {
        return stores.findAll().stream()
                .map(s -> new TicketViews.StoreView(s.getId(), s.getCode(), s.getName(), s.getTaxId()))
                .toList();
    }

    @GetMapping("/categories")
    public List<TicketViews.CategoryView> categories() {
        return categories.findAll().stream()
                .map(c -> new TicketViews.CategoryView(c.getId(), c.getCode(), c.getName(),
                        c.getParent() == null ? null : c.getParent().getId()))
                .sorted(java.util.Comparator.comparing(TicketViews.CategoryView::code))
                .toList();
    }

    /**
     * Busca productos del súper para asignarlos a mano. Con {@code q} usa la
     * similitud de trigramas sobre los alias, que es el mismo camino que el
     * matcher automático, así que lo que la persona ve es lo que el matcher vio.
     */
    @GetMapping("/stores/{storeId}/products")
    public List<TicketViews.ProductView> searchProducts(@PathVariable Long storeId,
                                                        @RequestParam(required = false) String q) {
        if (q == null || q.isBlank()) {
            return products.findByStoreId(storeId).stream().map(this::toView).toList();
        }

        String candidate = Optional.ofNullable(TextNormalizer.latinTail(q))
                .orElse(TextNormalizer.normalize(q));

        List<TicketViews.ProductView> byAlias = aliases.findSimilar(storeId, candidate, 0.2, 15).stream()
                .map(hit -> products.findById(hit.getStoreProductId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(this::toView)
                .toList();
        if (!byAlias.isEmpty()) {
            return byAlias;
        }

        // Un producto recién creado aún no tiene alias, así que se cae al nombre.
        String needle = TextNormalizer.normalize(q);
        return products.findByStoreId(storeId).stream()
                .filter(p -> TextNormalizer.normalize(p.getCanonicalName()).contains(needle)
                        || (p.getDisplayName() != null
                            && TextNormalizer.normalize(p.getDisplayName()).contains(needle)))
                .map(this::toView)
                .toList();
    }

    private TicketViews.ProductView toView(StoreProduct p) {
        return new TicketViews.ProductView(p.getId(), p.getCanonicalName(), p.getDisplayName(),
                p.getNotes(), p.getPackageSize(), p.getPackageUnit(),
                p.getSoldBy() == null ? null : p.getSoldBy().name(),
                p.getCategory() == null ? null : p.getCategory().getId());
    }
}
