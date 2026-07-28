package com.juanluidos.ticketing.domain;

/** Cómo se vende el producto, que determina si su precio es normalizable. */
public enum SoldBy {

    /** Envasado: base = cantidad × tamaño del envase. */
    PACKAGE,

    /** A peso: la línea trae peso y €/kg, base directa. */
    WEIGHT,

    /**
     * Pieza de peso variable sin peso ni €/kg impresos (los tubos de pota).
     * No normalizable: se guarda el €/pieza, {@code normalized_unit_price} queda
     * a null y queda fuera del conteo de subidas, porque la variación entre
     * piezas es peso, no subida de precio.
     */
    VARIABLE_PIECE
}
