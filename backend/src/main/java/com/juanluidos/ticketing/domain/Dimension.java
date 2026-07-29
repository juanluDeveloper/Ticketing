package com.juanluidos.ticketing.domain;

/**
 * Dimensión física de comparación, con su unidad canónica.
 *
 * <p>Toda la aplicación normaliza a esta unidad: la serie de precios
 * ({@code price_observation.normalized_unit_price}), el ranking del comparador y
 * la prima de preferencia ({@code user_product_preference.margin_value}) hablan
 * los tres en €/kg, €/L o €/ud. Guardar la serie en unidad base (€/g) y el
 * margen en unidad legible (€/kg) obligaría a convertir en el comparador, que es
 * justo donde un factor de 1000 pasa desapercibido.
 *
 * <p>La unidad depende solo de la dimensión, no del {@link ComparableGroup}, para
 * que un producto todavía sin agrupar tenga precio normalizado igualmente.
 *
 * <p>Un grupo no puede mezclar dimensiones: detergente en dosis
 * ("DET MARSE FLOTA 100D") y detergente en litros ("FLOTA LAVAV. 1,10L") no son
 * comparables entre sí.
 */
public enum Dimension {
    WEIGHT("kg"),
    VOLUME("L"),
    UNIT("ud");

    private final String canonicalUnit;

    Dimension(String canonicalUnit) {
        this.canonicalUnit = canonicalUnit;
    }

    public String getCanonicalUnit() {
        return canonicalUnit;
    }
}
