package com.juanluidos.ticketing.domain;

/**
 * Tipo de prima de preferencia (mecanismo A).
 *
 * <p>{@link #ABS} va en euros por unidad canónica de la dimensión (€/kg, €/L,
 * €/ud), la misma en la que se guarda {@code normalized_unit_price}: "prefiero
 * el vinagre del Mercadona hasta 0,15 €/L más caro" es legible, "0,00015 €/ml"
 * no, y mezclar las dos unidades mete un factor 1000 en el comparador.
 */
public enum MarginType {
    ABS,
    PCT
}
