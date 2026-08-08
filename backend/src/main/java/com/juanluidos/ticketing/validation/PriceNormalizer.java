package com.juanluidos.ticketing.validation;

import com.juanluidos.ticketing.domain.Dimension;
import com.juanluidos.ticketing.domain.PriceObservation;
import com.juanluidos.ticketing.domain.SoldBy;
import com.juanluidos.ticketing.domain.StoreProduct;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Precio por unidad canónica de una compra: €/kg, €/L o €/ud.
 *
 * <p>Vive aparte porque hay dos momentos que tienen que dar exactamente el mismo
 * número: cuando se confirma un ticket, y cuando alguien teclea por fin el
 * tamaño del envase de un producto que ya tenía compras. Si esa cuenta
 * estuviera duplicada, el histórico acabaría con dos tramos calculados con
 * reglas distintas y nadie sabría cuál mirar.
 */
@Component
public class PriceNormalizer {

    public record Normalized(BigDecimal price, String unit) {
    }

    /**
     * @param lineTotal   lo que costó la línea entera
     * @param quantity    cuántas piezas o envases
     * @param weightValue peso leído del ticket, solo en las líneas a peso
     */
    public Optional<Normalized> of(BigDecimal lineTotal,
                                   BigDecimal quantity,
                                   SoldBy soldBy,
                                   BigDecimal weightValue,
                                   String weightUnit,
                                   StoreProduct product) {
        if (lineTotal == null || quantity == null || quantity.signum() <= 0) {
            return Optional.empty();
        }
        if (soldBy == SoldBy.VARIABLE_PIECE) {
            // Sin peso ni precio por kilo en el ticket no hay nada que normalizar.
            return Optional.empty();
        }

        if (soldBy == SoldBy.WEIGHT && weightValue != null) {
            return UnitConverter.toCanonical(weightValue, weightUnit)
                    .filter(base -> base.signum() > 0)
                    .map(base -> new Normalized(
                            lineTotal.divide(base, 6, RoundingMode.HALF_UP),
                            Dimension.WEIGHT.getCanonicalUnit()));
        }

        if (product.getPackageSize() == null || product.getPackageUnit() == null) {
            // El tamaño del envase se teclea una vez por producto; hasta que
            // esté, el precio no es comparable entre súper.
            return Optional.empty();
        }
        return UnitConverter.toCanonical(product.getPackageSize(), product.getPackageUnit())
                .filter(size -> size.signum() > 0)
                .flatMap(size -> UnitConverter.dimensionOf(product.getPackageUnit())
                        .map(dimension -> new Normalized(
                                lineTotal.divide(quantity.multiply(size), 6, RoundingMode.HALF_UP),
                                dimension.getCanonicalUnit())));
    }

    /**
     * Recalcula el precio normalizado de una observación ya guardada y, con él,
     * si cuenta para el conteo de subidas.
     *
     * <p>Se usa al cambiar el tamaño del envase: sin esto, teclearlo solo
     * afectaría a las compras futuras y las anteriores se quedarían sin serie
     * para siempre.
     *
     * @return true si la observación queda con precio normalizado
     */
    public boolean reapply(PriceObservation observation, StoreProduct product) {
        var line = observation.getLineItem();
        SoldBy soldBy = line != null && line.getSoldBy() != null
                ? line.getSoldBy()
                : product.getSoldBy();
        BigDecimal quantity = observation.getQuantity() == null || observation.getQuantity().signum() <= 0
                ? BigDecimal.ONE
                : observation.getQuantity();

        Optional<Normalized> normalized = of(
                observation.getLineTotal(),
                quantity,
                soldBy,
                line == null ? null : line.getWeightValue(),
                line == null ? null : line.getWeightUnit(),
                product);

        observation.setNormalizedUnitPrice(normalized.map(Normalized::price).orElse(null));
        observation.setNormalizedUnit(normalized.map(Normalized::unit).orElse(null));
        // Una pieza de peso variable cambia de precio porque cambia de peso, no
        // porque haya subido: cuenta como precio pagado, no como serie de precios.
        observation.setCountsForIncrease(
                normalized.isPresent() && !observation.isPromo() && soldBy != SoldBy.VARIABLE_PIECE);
        return normalized.isPresent();
    }
}
