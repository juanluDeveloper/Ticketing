package com.juanluidos.ticketing.validation;

import com.juanluidos.ticketing.domain.LineItem;
import com.juanluidos.ticketing.domain.PriceObservation;
import com.juanluidos.ticketing.domain.SoldBy;
import com.juanluidos.ticketing.domain.StoreProduct;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El recálculo al teclear el tamaño del envase.
 *
 * <p>Lo que se protege aquí es la promesa del aviso: "todas sus compras pasan a
 * tener precio por unidad, <em>incluidas las ya registradas</em>". El precio
 * normalizado se guarda con cada observación, así que sin rehacerlas el tamaño
 * solo valdría para las compras futuras.
 */
class PriceNormalizerTest {

    private final PriceNormalizer normalizer = new PriceNormalizer();

    @Test
    void aPurchaseWithoutPackageSizeHasNoNormalizedPrice() {
        StoreProduct product = product(null, null, SoldBy.PACKAGE);
        PriceObservation observation = observation(product, "3.15", "1", line(SoldBy.PACKAGE, null, null));

        assertThat(normalizer.reapply(observation, product)).isFalse();
        assertThat(observation.getNormalizedUnitPrice()).isNull();
        assertThat(observation.isCountsForIncrease()).isFalse();
    }

    /** El caso del aguacate: dos compras ya hechas y el envase tecleado después. */
    @Test
    void typingThePackageSizeRebuildsThePurchasesAlreadyRecorded() {
        StoreProduct product = product(null, null, SoldBy.PACKAGE);
        PriceObservation first = observation(product, "3.15", "1", line(SoldBy.PACKAGE, null, null));
        PriceObservation second = observation(product, "3.08", "1", line(SoldBy.PACKAGE, null, null));
        normalizer.reapply(first, product);
        normalizer.reapply(second, product);
        assertThat(first.getNormalizedUnitPrice()).isNull();

        product.setPackageSize(new BigDecimal("4"));
        product.setPackageUnit("ud");

        assertThat(normalizer.reapply(first, product)).isTrue();
        assertThat(normalizer.reapply(second, product)).isTrue();
        assertThat(first.getNormalizedUnitPrice()).isEqualByComparingTo("0.7875");
        assertThat(second.getNormalizedUnitPrice()).isEqualByComparingTo("0.77");
        assertThat(first.getNormalizedUnit()).isEqualTo("ud");
        assertThat(first.isCountsForIncrease()).isTrue();
    }

    /** Dos envases en la misma línea: el precio por unidad divide por los dos. */
    @Test
    void quantityDividesToo() {
        StoreProduct product = product("1", "L", SoldBy.PACKAGE);
        PriceObservation observation = observation(product, "2.26", "2", line(SoldBy.PACKAGE, null, null));

        normalizer.reapply(observation, product);

        assertThat(observation.getNormalizedUnitPrice()).isEqualByComparingTo("1.13");
    }

    /** Gramos y mililitros suben a la unidad canónica: kg y L. */
    @Test
    void gramsBecomeKilos() {
        StoreProduct product = product("400", "g", SoldBy.PACKAGE);
        PriceObservation observation = observation(product, "3.35", "1", line(SoldBy.PACKAGE, null, null));

        normalizer.reapply(observation, product);

        assertThat(observation.getNormalizedUnitPrice()).isEqualByComparingTo("8.375000");
        assertThat(observation.getNormalizedUnit()).isEqualTo("kg");
    }

    /**
     * Una línea a peso ya trae su €/kg del propio ticket. Tocar el envase del
     * producto no puede movérselo: se calculó con el peso impreso, que es un
     * dato mejor.
     */
    @Test
    void aWeightLineIgnoresThePackageSize() {
        StoreProduct product = product("1", "kg", SoldBy.WEIGHT);
        PriceObservation observation =
                observation(product, "8.31", "1", line(SoldBy.WEIGHT, "0.260", "kg"));

        normalizer.reapply(observation, product);

        // 8,31 / 0,260 kg = 31,96 €/kg, el precio del mostrador.
        assertThat(observation.getNormalizedUnitPrice()).isEqualByComparingTo("31.961538");
        assertThat(observation.getNormalizedUnit()).isEqualTo("kg");
    }

    /** Una pieza de peso variable no entra en la serie por mucho envase que tenga. */
    @Test
    void aVariablePieceStaysOutOfTheSeries() {
        StoreProduct product = product("1", "ud", SoldBy.VARIABLE_PIECE);
        PriceObservation observation =
                observation(product, "1.82", "1", line(SoldBy.VARIABLE_PIECE, null, null));

        assertThat(normalizer.reapply(observation, product)).isFalse();
        assertThat(observation.isCountsForIncrease()).isFalse();
    }

    /** Una oferta se normaliza —es lo que pagaste— pero no cuenta como subida. */
    @Test
    void aPromoIsNormalizedButDoesNotCount() {
        StoreProduct product = product("1", "L", SoldBy.PACKAGE);
        PriceObservation observation = observation(product, "0.69", "1", line(SoldBy.PACKAGE, null, null));
        observation.setPromo(true);

        assertThat(normalizer.reapply(observation, product)).isTrue();
        assertThat(observation.getNormalizedUnitPrice()).isEqualByComparingTo("0.69");
        assertThat(observation.isCountsForIncrease()).isFalse();
    }

    /** Una unidad que el conversor no entiende deja la compra sin serie, no a cero. */
    @Test
    void anUnknownUnitLeavesThePurchaseWithoutSeries() {
        StoreProduct product = product("1", "bote", SoldBy.PACKAGE);
        PriceObservation observation = observation(product, "2.50", "1", line(SoldBy.PACKAGE, null, null));

        assertThat(normalizer.reapply(observation, product)).isFalse();
        assertThat(observation.getNormalizedUnitPrice()).isNull();
    }

    // ------------------------------------------------------------------

    private StoreProduct product(String size, String unit, SoldBy soldBy) {
        StoreProduct product = new StoreProduct();
        product.setCanonicalName("PRODUCTO");
        product.setPackageSize(size == null ? null : new BigDecimal(size));
        product.setPackageUnit(unit);
        product.setSoldBy(soldBy);
        return product;
    }

    private LineItem line(SoldBy soldBy, String weight, String weightUnit) {
        LineItem line = new LineItem();
        line.setSoldBy(soldBy);
        line.setWeightValue(weight == null ? null : new BigDecimal(weight));
        line.setWeightUnit(weightUnit);
        return line;
    }

    private PriceObservation observation(StoreProduct product, String total, String quantity, LineItem line) {
        PriceObservation observation = new PriceObservation();
        observation.setStoreProduct(product);
        observation.setLineItem(line);
        observation.setLineTotal(new BigDecimal(total));
        observation.setQuantity(new BigDecimal(quantity));
        return observation;
    }
}
