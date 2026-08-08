package com.juanluidos.ticketing.history;

import com.juanluidos.ticketing.domain.PriceObservation;
import com.juanluidos.ticketing.domain.SoldBy;
import com.juanluidos.ticketing.domain.StoreProduct;
import com.juanluidos.ticketing.repository.PriceObservationRepository;
import com.juanluidos.ticketing.repository.StoreProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Histórico de precios por producto.
 *
 * <p>El número delicado es el de subidas. Se calcula solo sobre la serie limpia —
 * sin promociones y sin piezas de peso variable, que cambian de precio porque
 * cambian de peso — y con dos filtros más que evitan contar ruido como inflación.
 */
@Service
public class ProductHistoryService {

    /** Un céntimo: por debajo es redondeo del importe, no un cambio de precio. */
    private static final BigDecimal MIN_ABSOLUTE_STEP = new BigDecimal("0.01");
    /** Medio por ciento: en €/kg, dos céntimos sobre veinte euros no son una subida. */
    private static final BigDecimal MIN_RELATIVE_STEP = new BigDecimal("0.005");

    private final StoreProductRepository products;
    private final PriceObservationRepository observations;

    public ProductHistoryService(StoreProductRepository products,
                                 PriceObservationRepository observations) {
        this.products = products;
        this.observations = observations;
    }

    @Transactional(readOnly = true)
    public PriceHistory of(Long storeProductId) {
        StoreProduct product = products.findById(storeProductId).orElseThrow();
        List<PriceObservation> all =
                observations.findByStoreProductIdOrderByObservedAtAsc(storeProductId);

        List<PriceHistory.Point> points = all.stream()
                .filter(o -> o.getObservedAt() != null)
                .map(this::toPoint)
                .sorted(Comparator.comparing(PriceHistory.Point::date))
                .toList();

        return new PriceHistory(toInfo(product), points, metrics(all, points),
                notComparableReason(product, points));
    }

    // ------------------------------------------------------------------

    private PriceHistory.Metrics metrics(List<PriceObservation> all, List<PriceHistory.Point> points) {
        List<BigDecimal> clean = cleanSeries(points);

        BigDecimal totalSpent = all.stream()
                .map(PriceObservation::getLineTotal)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        var lastNormalized = points.stream()
                .filter(p -> p.normalizedUnitPrice() != null)
                .reduce((a, b) -> b);

        var first = all.stream().map(PriceObservation::getObservedAt)
                .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder());
        var last = all.stream().map(PriceObservation::getObservedAt)
                .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder());

        int increases = 0;
        int decreases = 0;
        for (int i = 1; i < clean.size(); i++) {
            int direction = stepDirection(clean.get(i - 1), clean.get(i));
            if (direction > 0) {
                increases++;
            } else if (direction < 0) {
                decreases++;
            }
        }

        return new PriceHistory.Metrics(
                all.size(),
                first.orElse(null),
                last.orElse(null),
                last.map(l -> (int) ChronoUnit.DAYS.between(l.toLocalDate(), LocalDate.now())).orElse(null),
                lastNormalized.map(PriceHistory.Point::normalizedUnitPrice).orElse(null),
                lastNormalized.map(PriceHistory.Point::normalizedUnit).orElse(null),
                clean.stream().min(Comparator.naturalOrder()).orElse(null),
                clean.stream().max(Comparator.naturalOrder()).orElse(null),
                increases,
                decreases,
                volatility(clean),
                totalSpent,
                clean.size(),
                points.size() - clean.size());
    }

    /**
     * La serie sobre la que se cuentan subidas: solo puntos normalizables que
     * cuentan, y uno por día.
     *
     * <p>El colapso por día no es cosmético. Tres tubos de pota en el mismo
     * ticket, o dos envases del mismo producto, generan varias observaciones con
     * el mismo precio de estantería: contarlas por separado inventaría subidas y
     * bajadas que no existen.
     */
    private List<BigDecimal> cleanSeries(List<PriceHistory.Point> points) {
        Map<LocalDate, BigDecimal> byDay = new LinkedHashMap<>();
        for (PriceHistory.Point point : points) {
            if (point.countsForIncrease() && point.normalizedUnitPrice() != null) {
                byDay.put(point.date(), point.normalizedUnitPrice());
            }
        }
        return new ArrayList<>(byDay.values());
    }

    /**
     * @return 1 si es subida, -1 si es bajada, 0 si el cambio no supera los dos
     *         umbrales y por tanto es ruido
     */
    private int stepDirection(BigDecimal previous, BigDecimal current) {
        BigDecimal delta = current.subtract(previous);
        if (delta.abs().compareTo(MIN_ABSOLUTE_STEP) < 0) {
            return 0;
        }
        if (previous.signum() > 0) {
            BigDecimal relative = delta.abs().divide(previous, 6, RoundingMode.HALF_UP);
            if (relative.compareTo(MIN_RELATIVE_STEP) < 0) {
                return 0;
            }
        }
        return delta.signum();
    }

    /** Coeficiente de variación: desviación típica sobre la media. */
    private BigDecimal volatility(List<BigDecimal> series) {
        if (series.size() < 2) {
            return null;
        }
        double mean = series.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        if (mean == 0) {
            return null;
        }
        double variance = series.stream()
                .mapToDouble(v -> Math.pow(v.doubleValue() - mean, 2))
                .sum() / (series.size() - 1);
        return BigDecimal.valueOf(Math.sqrt(variance) / mean).setScale(4, RoundingMode.HALF_UP);
    }

    private String notComparableReason(StoreProduct product, List<PriceHistory.Point> points) {
        boolean anyNormalized = points.stream().anyMatch(p -> p.normalizedUnitPrice() != null);
        if (anyNormalized) {
            return null;
        }
        if (points.isEmpty()) {
            return "Todavía no hay ninguna compra registrada de este producto.";
        }
        if (product.getSoldBy() == SoldBy.VARIABLE_PIECE) {
            return "Es una pieza de peso variable: el ticket solo imprime el precio final, sin "
                    + "peso ni precio por kilo, así que no hay nada que normalizar. Se guarda el "
                    + "precio por pieza y queda fuera del conteo de subidas, porque lo que varía "
                    + "es el peso, no el precio.";
        }
        if (product.getPackageSize() == null || product.getPackageUnit() == null) {
            return "Falta el tamaño del envase. Ponlo una vez en el producto y todas sus compras "
                    + "pasan a tener precio por unidad, incluidas las ya registradas.";
        }
        return "Sin precio normalizado en ninguna de las compras registradas.";
    }

    private PriceHistory.Point toPoint(PriceObservation observation) {
        return new PriceHistory.Point(
                observation.getObservedAt().toLocalDate(),
                observation.getPricePerPiece(),
                observation.getNormalizedUnitPrice(),
                observation.getNormalizedUnit(),
                observation.isPromo(),
                observation.isCountsForIncrease(),
                observation.getLineItem() == null || observation.getLineItem().getTicket() == null
                        ? null
                        : observation.getLineItem().getTicket().getId());
    }

    private PriceHistory.ProductInfo toInfo(StoreProduct product) {
        return new PriceHistory.ProductInfo(
                product.getId(),
                product.getStore().getCode(),
                product.getStore().getName(),
                product.getCanonicalName(),
                product.getDisplayName(),
                product.getNotes(),
                product.getPackageSize(),
                product.getPackageUnit(),
                product.getSoldBy() == null ? null : product.getSoldBy().name(),
                product.getDeclaredUnitPrice(),
                product.getDeclaredUnit(),
                product.getDeclaredAt() == null ? null : product.getDeclaredAt().toLocalDate());
    }
}
