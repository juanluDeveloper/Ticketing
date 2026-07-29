package com.juanluidos.ticketing.validation;

import com.juanluidos.ticketing.domain.CheckCode;
import com.juanluidos.ticketing.domain.IssueSeverity;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado de pasar las comprobaciones a una extracción.
 *
 * <p>Deliberadamente NO dice "el ticket está bien". Dice qué comprobaciones se
 * pudieron aplicar, cuáles pasaron y cuánta de la extracción ha quedado sin
 * mirar. Un ticket con todas en verde y cobertura del 20 % necesita más revisión
 * humana que uno con una en rojo y cobertura del 100 %, porque en el primero las
 * checks apenas han podido decir nada.
 */
public record CheckReport(
        List<CheckOutcome> outcomes,
        List<LineFinding> findings,
        int lineCount,
        int coveredLines,
        BigDecimal coverageRatio
) {

    /**
     * @param applicable    falso cuando el formato del súper no imprime lo que la
     *                      comprobación necesita
     * @param passed        null si no es aplicable; nunca true por omisión
     * @param linesCovered  cuántas líneas ha podido mirar esta comprobación
     */
    public record CheckOutcome(
            CheckCode code,
            boolean applicable,
            Boolean passed,
            Integer linesCovered,
            String detail
    ) {
        public static CheckOutcome notApplicable(CheckCode code, String why) {
            return new CheckOutcome(code, false, null, 0, why);
        }

        public static CheckOutcome of(CheckCode code, boolean passed, int linesCovered, String detail) {
            return new CheckOutcome(code, true, passed, linesCovered, detail);
        }
    }

    public record LineFinding(
            Integer lineNo,
            CheckCode code,
            IssueSeverity severity,
            String message,
            BigDecimal expected,
            BigDecimal actual
    ) {
    }

    public boolean hasErrors() {
        return findings.stream().anyMatch(f -> f.severity() == IssueSeverity.ERROR);
    }

    public boolean anyCheckFailed() {
        return outcomes.stream().anyMatch(o -> Boolean.FALSE.equals(o.passed()));
    }
}
