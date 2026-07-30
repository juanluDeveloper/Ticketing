package com.juanluidos.ticketing.validation;

import com.juanluidos.ticketing.domain.SoldBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Lo que manda la pantalla de validación: las correcciones de la persona.
 *
 * <p>{@code confirm} distingue guardar el progreso de dar el ticket por bueno.
 * Se puede corregir en varias pasadas y confirmar al final, que es lo natural en
 * un ticket de 27 líneas.
 */
public record ValidationRequest(
        LocalDateTime purchasedAt,
        String receiptNumber,
        BigDecimal total,
        Integer articleCount,
        List<LineUpdate> lines,
        boolean confirm
) {

    public record LineUpdate(
            Long lineItemId,
            /**
             * Borra la línea. Hace falta porque el extractor genera líneas
             * espurias de verdad: la sub-línea de peso del mango salió como
             * ítem propio, duplicando su importe y rompiendo C1. Sin poder
             * quitarla, ese ticket no se puede validar nunca.
             */
            Boolean delete,
            String rawDescription,
            BigDecimal quantity,
            BigDecimal printedUnitPrice,
            BigDecimal lineTotal,
            String taxLetter,
            SoldBy soldBy,
            Boolean promo,
            BigDecimal weightValue,
            String weightUnit,
            ProductDecision product
    ) {
    }

    /** Exactamente una de las tres opciones. */
    public record ProductDecision(
            Long existingStoreProductId,
            NewProduct newProduct,
            boolean unassign
    ) {
    }

    /**
     * Producto nuevo creado desde la validación. {@code displayName} y
     * {@code notes} son lo que hace usables los productos de Xinya: la traducción
     * se teclea una vez y se reutiliza vía alias.
     */
    public record NewProduct(
            String canonicalName,
            String displayName,
            String notes,
            String brand,
            BigDecimal packageSize,
            String packageUnit,
            Long categoryId,
            SoldBy soldBy
    ) {
    }
}
