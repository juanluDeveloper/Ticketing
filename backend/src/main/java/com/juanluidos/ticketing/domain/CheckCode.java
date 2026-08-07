package com.juanluidos.ticketing.domain;

/**
 * Comprobaciones del motor de validación.
 *
 * <p>Cada una necesita que el ticket traiga cierta redundancia impresa, y esa
 * redundancia depende del formato de cada súper. Una comprobación cuyo formato
 * no la soporta se registra como <em>no aplicable</em>, nunca como pasada:
 * darla por verde por omisión infla la confianza en la extracción.
 *
 * <p>Ninguna de estas sustituye la revisión humana. Su trabajo es degradar la
 * pantalla de validación de "corregir" a "confirmar" y señalar dónde mirar.
 * Ver {@code docs/hallazgos_verificacion_tickets.md} §1.
 */
public enum CheckCode {

    /** Σ importes de línea == total impreso. No detecta desalineamiento: la suma no cambia. */
    C1("Suma de líneas contra el total impreso"),

    /**
     * cantidad × precio unitario == importe de línea. Detecta desalineamiento y
     * cantidades mal leídas, pero solo cubre las líneas que traen P.Unit impreso.
     */
    C2("Cantidad por precio unitario contra el importe de línea"),

    /**
     * Σ importes agrupados por letra de IVA contra la base más la cuota impresas
     * para ese tipo. Solo desambigua si el ticket mezcla letras.
     */
    C3("Importes por letra de IVA contra el desglose impreso"),

    /** Σ cantidades == recuento de artículos impreso. Detecta líneas perdidas, no reordenadas. */
    C4("Recuento de artículos"),

    /** Σ bases + Σ cuotas == total. Valida la lectura de la tabla de IVA. */
    C5("Bases más cuotas contra el total"),

    /**
     * Heurística, no aritmética: la letra de IVA de la línea no coincide con la
     * habitual del producto ya conocido. Requiere histórico, así que no aporta
     * nada las primeras semanas.
     */
    H1("Letra de IVA distinta de la habitual del producto"),

    /**
     * Heurística: la misma descripción aparece varias veces en el ticket con
     * cantidad 1 e importes distintos. Sugiere {@link SoldBy#VARIABLE_PIECE}
     * (el caso de los tubos de pota).
     */
    H2("Posible pieza de peso variable"),

    /**
     * Heurística semántica: el ticket entero sale clasificado como "a peso" o
     * como "pieza de peso variable". Las dos son excepciones — lo normal es que
     * casi todo sea envasado — así que un ticket uniforme en cualquiera de ellas
     * es casi seguro una mala clasificación, no un ticket raro.
     *
     * <p>Importa porque se cuela en silencio: ninguna comprobación aritmética
     * mira el {@code sold_by}, y clasificar mal deja el ticket entero fuera del
     * precio normalizado y del conteo de subidas sin que nada chille.
     */
    H3("Todo el ticket con la misma clasificación de venta"),

    /**
     * Heurística: la fila transcrita no contiene el importe de la línea, así que
     * la transcripción en dos etapas no ha ocurrido de verdad y la defensa
     * contra el desplazamiento de columnas no está en efecto.
     */
    H4("Fila transcrita sin el importe");

    private final String description;

    CheckCode(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
