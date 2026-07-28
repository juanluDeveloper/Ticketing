package com.juanluidos.ticketing.domain;

/** Gravedad de un hallazgo de validación. ERROR bloquea el paso a VALIDATED. */
public enum IssueSeverity {
    ERROR,
    WARN,
    INFO
}
