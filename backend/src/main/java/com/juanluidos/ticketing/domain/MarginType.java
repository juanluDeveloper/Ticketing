package com.juanluidos.ticketing.domain;

/**
 * Tipo de prima de preferencia (mecanismo A).
 *
 * <p>{@link #ABS} va en euros por <em>unidad de comparación del grupo</em>
 * (€/kg, €/L, €/ud), no por unidad base: "prefiero el vinagre del Mercadona
 * hasta 0,15 €/L más caro" es legible, "0,00015 €/ml" no.
 */
public enum MarginType {
    ABS,
    PCT
}
