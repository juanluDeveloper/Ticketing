package com.juanluidos.ticketing.domain;

/** Cómo se resolvió el emparejamiento línea → producto. */
public enum MatchMethod {

    /** Alias normalizado idéntico ya visto en ese súper. Primera pasada, la más barata. */
    EXACT_ALIAS,

    /** Similitud de trigramas (pg_trgm). Solo para lo que no casa exacto. */
    TRIGRAM,

    /** Elegido a mano en la pantalla de validación. */
    MANUAL,

    /** Producto creado nuevo desde esta línea. */
    NEW_PRODUCT
}
