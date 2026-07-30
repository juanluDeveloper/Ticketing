package com.juanluidos.ticketing.matching;

import com.juanluidos.ticketing.domain.MatchMethod;
import com.juanluidos.ticketing.domain.ProductAlias;
import com.juanluidos.ticketing.domain.Store;
import com.juanluidos.ticketing.domain.StoreProduct;
import com.juanluidos.ticketing.repository.ProductAliasRepository;
import com.juanluidos.ticketing.repository.StoreProductRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Empareja una descripción cruda con el producto de ese súper.
 *
 * <p>Dos pasadas, la barata primero: coincidencia exacta del alias normalizado,
 * que resuelve la mayoría de las líneas porque los tickets repiten la misma
 * cadena; y solo si falla, similitud de trigramas.
 *
 * <p>Nunca decide: <em>sugiere</em>. Confirmar es de la persona, y cada
 * confirmación añade un alias, así que mejora solo.
 */
@Component
public class ProductMatcher {

    /** Por debajo de esto la sugerencia estorba más que ayuda. */
    private static final double TRIGRAM_THRESHOLD = 0.35;

    private final ProductAliasRepository aliases;
    private final StoreProductRepository products;

    public ProductMatcher(ProductAliasRepository aliases, StoreProductRepository products) {
        this.aliases = aliases;
        this.products = products;
    }

    public record Suggestion(StoreProduct product, BigDecimal confidence, MatchMethod method) {
    }

    public Optional<Suggestion> suggest(Long storeId, String rawDescription) {
        String normalized = TextNormalizer.normalize(rawDescription);
        if (normalized == null || normalized.isBlank()) {
            return Optional.empty();
        }

        Optional<ProductAlias> exact = aliases.findByStoreIdAndNormalizedText(storeId, normalized);
        if (exact.isPresent()) {
            return Optional.of(new Suggestion(
                    exact.get().getStoreProduct(), BigDecimal.ONE, MatchMethod.EXACT_ALIAS));
        }

        // Para las descripciones con chino se compara la cola latina, porque
        // pg_trgm no rinde sobre ideogramas.
        String candidate = Optional.ofNullable(TextNormalizer.latinTail(rawDescription))
                .orElse(normalized);

        return aliases.findSimilar(storeId, candidate, TRIGRAM_THRESHOLD, 1).stream()
                .findFirst()
                .flatMap(hit -> products.findById(hit.getStoreProductId())
                        .map(p -> new Suggestion(p,
                                BigDecimal.valueOf(hit.getSimilarity()).setScale(4, java.math.RoundingMode.HALF_UP),
                                MatchMethod.TRIGRAM)));
    }

    /**
     * Registra la cadena con la que ha aparecido el producto. Si ya se conocía,
     * solo sube el contador: los alias repetidos no aportan.
     */
    public void rememberAlias(StoreProduct product, Store store, String rawDescription) {
        String normalized = TextNormalizer.normalize(rawDescription);
        if (normalized == null || normalized.isBlank()) {
            return;
        }

        Optional<ProductAlias> existing = aliases.findByStoreIdAndNormalizedText(store.getId(), normalized);
        if (existing.isPresent()) {
            ProductAlias alias = existing.get();
            alias.setTimesSeen(alias.getTimesSeen() + 1);
            alias.setLastSeenAt(LocalDateTime.now());
            aliases.save(alias);
            return;
        }

        ProductAlias alias = new ProductAlias();
        alias.setStoreProduct(product);
        alias.setStore(store);
        alias.setRawText(rawDescription);
        alias.setNormalizedText(normalized);
        alias.setLatinText(TextNormalizer.latinTail(rawDescription));
        aliases.save(alias);
    }
}
