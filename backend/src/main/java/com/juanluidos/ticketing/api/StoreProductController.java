package com.juanluidos.ticketing.api;

import com.juanluidos.ticketing.domain.PriceObservation;
import com.juanluidos.ticketing.domain.StoreProduct;
import com.juanluidos.ticketing.history.PriceHistory;
import com.juanluidos.ticketing.history.ProductHistoryService;
import com.juanluidos.ticketing.repository.PriceObservationRepository;
import com.juanluidos.ticketing.repository.StoreProductRepository;
import com.juanluidos.ticketing.validation.PriceNormalizer;
import com.juanluidos.ticketing.validation.UnitConverter;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Editar un producto ya creado.
 *
 * <p>Hasta ahora el tamaño del envase solo se podía poner al crear el producto
 * desde la validación de un ticket. El aviso "falta el tamaño del envase, ponlo
 * una vez" no llevaba a ninguna parte: no había dónde ponerlo.
 */
@RestController
@RequestMapping("/api/products")
public class StoreProductController {

    private final StoreProductRepository products;
    private final PriceObservationRepository observations;
    private final ProductHistoryService history;
    private final PriceNormalizer normalizer;

    public StoreProductController(StoreProductRepository products,
                                  PriceObservationRepository observations,
                                  ProductHistoryService history,
                                  PriceNormalizer normalizer) {
        this.products = products;
        this.observations = observations;
        this.history = history;
        this.normalizer = normalizer;
    }

    /**
     * Solo lo que una persona corrige a mano. El nombre canónico no se toca: es
     * lo que imprime el ticket y con lo que empareja el matcher.
     */
    public record ProductUpdate(
            String displayName,
            String notes,
            BigDecimal packageSize,
            String packageUnit
    ) {
    }

    @PatchMapping("/{id}")
    @Transactional
    public PriceHistory update(@PathVariable Long id, @RequestBody ProductUpdate update) {
        StoreProduct product = products.findById(id).orElseThrow();

        String unit = blankToNull(update.packageUnit());
        BigDecimal size = update.packageSize();

        // Se rechaza antes de guardar y con el motivo escrito: un tamaño que el
        // conversor no entiende deja el producto igual de incomparable que
        // estaba, pero además con un dato puesto que parece bueno.
        if (unit != null && UnitConverter.dimensionOf(unit).isEmpty()) {
            throw new IllegalArgumentException(
                    "Unidad de envase no reconocida: \"" + unit + "\". Se admiten kg, g, L, ml, cl y ud.");
        }
        if (size != null && size.signum() <= 0) {
            throw new IllegalArgumentException("El tamaño del envase tiene que ser mayor que cero.");
        }
        if ((size == null) != (unit == null)) {
            throw new IllegalArgumentException(
                    "El tamaño del envase necesita número y unidad: \"1\" y \"L\", por ejemplo.");
        }

        boolean sizeChanged = !Objects.equals(product.getPackageSize(), size)
                || !Objects.equals(product.getPackageUnit(), unit);

        product.setDisplayName(blankToNull(update.displayName()));
        product.setNotes(blankToNull(update.notes()));
        product.setPackageSize(size);
        product.setPackageUnit(unit == null ? null : unit.trim());
        products.save(product);

        if (sizeChanged) {
            recalculate(product);
        }
        return history.of(id);
    }

    /**
     * El precio normalizado se guarda con cada observación, así que cambiar el
     * envase no basta: hay que rehacer las compras ya registradas. Sin esto, el
     * aviso mentiría a medias — el tamaño valdría para las próximas y las de
     * antes se quedarían fuera de la serie para siempre.
     */
    private void recalculate(StoreProduct product) {
        List<PriceObservation> all =
                observations.findByStoreProductIdOrderByObservedAtAsc(product.getId());
        for (PriceObservation observation : all) {
            normalizer.reapply(observation, product);
        }
        observations.saveAll(all);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
