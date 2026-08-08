package com.juanluidos.ticketing.api;

import com.juanluidos.ticketing.domain.PriceObservation;
import com.juanluidos.ticketing.domain.SoldBy;
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
            String packageUnit,
            /** PACKAGE, WEIGHT o VARIABLE_PIECE. Null lo deja como está. */
            String soldBy,
            /** Precio del mostrador, tecleado a mano. Solo para el comparador. */
            BigDecimal declaredUnitPrice,
            /** La unidad de ese precio tal cual se teclee: kg, g, L, ml, cl o ud. */
            String declaredUnit
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

        SoldBy soldBy = parseSoldBy(update.soldBy(), product.getSoldBy());

        boolean affectsPrices = !Objects.equals(product.getPackageSize(), size)
                || !Objects.equals(product.getPackageUnit(), unit)
                || soldBy != product.getSoldBy();

        product.setDisplayName(blankToNull(update.displayName()));
        product.setNotes(blankToNull(update.notes()));
        product.setPackageSize(size);
        product.setPackageUnit(unit == null ? null : unit.trim());
        product.setSoldBy(soldBy);
        applyDeclaredPrice(product, update);
        products.save(product);

        if (affectsPrices) {
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

    /**
     * El precio declarado se guarda en unidad canónica: si se teclea en €/g, lo
     * que se compara luego son €/kg, y hacer la conversión aquí evita que el
     * ranking tenga que adivinar en qué unidad está cada miembro.
     *
     * <p>La fecha solo se mueve cuando el precio cambia de verdad. Guardar el
     * producto para corregir una nota no puede rejuvenecer un precio que sigue
     * siendo el mismo de hace medio año: esa fecha es lo que hace que el aviso
     * de calidad del dato signifique algo.
     */
    private void applyDeclaredPrice(StoreProduct product, ProductUpdate update) {
        BigDecimal price = update.declaredUnitPrice();
        String unit = blankToNull(update.declaredUnit());

        if (price == null && unit == null) {
            product.setDeclaredUnitPrice(null);
            product.setDeclaredUnit(null);
            product.setDeclaredAt(null);
            return;
        }
        if (price == null || unit == null) {
            throw new IllegalArgumentException(
                    "El precio declarado necesita importe y unidad: \"15\" y \"kg\", por ejemplo.");
        }
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("El precio declarado tiene que ser mayor que cero.");
        }

        // €/g -> €/kg es dividir entre el factor, no multiplicarlo: un gramo es
        // la milésima parte de un kilo, así que el kilo cuesta mil veces más.
        BigDecimal factor = UnitConverter.toCanonical(BigDecimal.ONE, unit)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unidad no reconocida para el precio declarado: \"" + unit
                                + "\". Se admiten kg, g, L, ml, cl y ud."));
        String canonicalUnit = UnitConverter.dimensionOf(unit).orElseThrow().getCanonicalUnit();
        BigDecimal canonicalPrice = price.divide(factor, 4, java.math.RoundingMode.HALF_UP);

        boolean changed = product.getDeclaredUnitPrice() == null
                || canonicalPrice.compareTo(product.getDeclaredUnitPrice()) != 0
                || !canonicalUnit.equals(product.getDeclaredUnit());

        product.setDeclaredUnitPrice(canonicalPrice);
        product.setDeclaredUnit(canonicalUnit);
        if (changed || product.getDeclaredAt() == null) {
            product.setDeclaredAt(java.time.LocalDateTime.now());
        }
    }

    private SoldBy parseSoldBy(String raw, SoldBy current) {
        if (blankToNull(raw) == null) {
            return current;
        }
        try {
            return SoldBy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Tipo de venta desconocido: \"" + raw + "\".");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
