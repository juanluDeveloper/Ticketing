package com.juanluidos.ticketing.comparison;

import com.juanluidos.ticketing.domain.*;
import com.juanluidos.ticketing.repository.ComparableGroupRepository;
import com.juanluidos.ticketing.repository.PriceObservationRepository;
import com.juanluidos.ticketing.repository.StoreProductRepository;
import com.juanluidos.ticketing.repository.UserProductPreferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Ranking entre súper dentro de un grupo comparable.
 *
 * <p>Los precios son "de la última vez que compré ahí", no en vivo. Eso no es un
 * defecto que ocultar: se enseña la fecha de cada precio y se avisa cuando la
 * comparación mezcla datos de antigüedades muy distintas, porque comparar un
 * precio de esta semana con otro de hace medio año no dice gran cosa.
 */
@Service
public class ComparisonService {

    /** A partir de aquí, comparar precios de fechas tan distintas es dudoso. */
    private static final int AGE_SPREAD_WARNING_DAYS = 60;
    /** Un precio más viejo que esto ya merece aviso por sí solo. */
    private static final int STALE_WARNING_DAYS = 120;

    private final ComparableGroupRepository groups;
    private final StoreProductRepository products;
    private final PriceObservationRepository observations;
    private final UserProductPreferenceRepository preferences;

    public ComparisonService(ComparableGroupRepository groups, StoreProductRepository products,
                             PriceObservationRepository observations,
                             UserProductPreferenceRepository preferences) {
        this.groups = groups;
        this.products = products;
        this.observations = observations;
        this.preferences = preferences;
    }

    @Transactional(readOnly = true)
    public GroupComparison compare(Long groupId, Long userId) {
        ComparableGroup group = groups.findById(groupId).orElseThrow();
        List<StoreProduct> members = products.findByComparableGroupId(groupId);

        Optional<UserProductPreference> preference =
                preferences.findByUserIdAndComparableGroupId(userId, groupId);
        Long preferredId = preference
                .map(p -> p.getPreferredStoreProduct().getId())
                .orElse(null);

        List<GroupComparison.Entry> comparable = new ArrayList<>();
        List<GroupComparison.Entry> notComparable = new ArrayList<>();

        for (StoreProduct member : members) {
            boolean preferred = member.getId().equals(preferredId);
            Optional<PriceObservation> latest = latestUsableObservation(member);

            if (latest.isEmpty()) {
                notComparable.add(notComparableEntry(member, preferred));
            } else {
                comparable.add(comparableEntry(member, latest.get(), preferred, preference));
            }
        }

        comparable.sort(Comparator.comparing(GroupComparison.Entry::normalizedUnitPrice));

        return new GroupComparison(
                toInfo(group, members.size()),
                comparable,
                notComparable,
                verdict(comparable, preference),
                dataWarning(comparable, notComparable));
    }

    // ------------------------------------------------------------------

    /**
     * El último precio de estantería: se ignoran las promociones, porque el
     * comparador responde a "dónde sale más barato normalmente", no a "qué pagué
     * el día que había oferta".
     */
    private Optional<PriceObservation> latestUsableObservation(StoreProduct product) {
        return observations.findByStoreProductIdOrderByObservedAtAsc(product.getId()).stream()
                .filter(o -> o.getNormalizedUnitPrice() != null)
                .filter(o -> !o.isPromo())
                .reduce((a, b) -> b);
    }

    private GroupComparison.Entry comparableEntry(StoreProduct product, PriceObservation observation,
                                                  boolean preferred,
                                                  Optional<UserProductPreference> preference) {
        LocalDate observedAt = observation.getObservedAt().toLocalDate();
        BigDecimal price = observation.getNormalizedUnitPrice();

        BigDecimal adjusted = preferred
                ? preference.map(p -> applyMargin(price, p)).orElse(null)
                : null;

        return new GroupComparison.Entry(
                product.getId(),
                product.getStore().getCode(),
                product.getStore().getName(),
                displayName(product),
                price,
                observation.getNormalizedUnit(),
                observedAt,
                (int) ChronoUnit.DAYS.between(observedAt, LocalDate.now()),
                observation.isPromo(),
                preferred,
                adjusted,
                null);
    }

    private GroupComparison.Entry notComparableEntry(StoreProduct product, boolean preferred) {
        return new GroupComparison.Entry(
                product.getId(),
                product.getStore().getCode(),
                product.getStore().getName(),
                displayName(product),
                null, null, null, null, false, preferred, null,
                notComparableReason(product));
    }

    /** Decir cuál de los tres motivos es, porque dos de ellos se arreglan. */
    private String notComparableReason(StoreProduct product) {
        List<PriceObservation> all =
                observations.findByStoreProductIdOrderByObservedAtAsc(product.getId());

        if (all.isEmpty()) {
            return "Nunca lo he comprado en " + product.getStore().getName()
                    + ", así que no hay precio con el que comparar.";
        }
        if (product.getSoldBy() == SoldBy.VARIABLE_PIECE) {
            return "Pieza de peso variable: el ticket no imprime peso ni precio por kilo, "
                    + "así que no se puede pasar a precio por unidad.";
        }
        if (product.getPackageSize() == null || product.getPackageUnit() == null) {
            return "Falta el tamaño del envase. Ponlo una vez y entra en la comparación con "
                    + "las compras que ya hay registradas.";
        }
        return "Todas las compras registradas son de oferta, y el comparador usa precio de "
                + "estantería. Hará falta una compra a precio normal.";
    }

    /**
     * Mecanismo A: al preferido se le resta la prima antes de comparar. Si con la
     * prima descontada sigue ganando, la preferencia se respeta.
     */
    private BigDecimal applyMargin(BigDecimal price, UserProductPreference preference) {
        BigDecimal margin = preference.getMarginValue() == null
                ? BigDecimal.ZERO
                : preference.getMarginValue();

        BigDecimal discount = preference.getMarginType() == MarginType.PCT
                ? price.multiply(margin).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                : margin;

        return price.subtract(discount).max(BigDecimal.ZERO);
    }

    private GroupComparison.Verdict verdict(List<GroupComparison.Entry> ranking,
                                            Optional<UserProductPreference> preference) {
        if (ranking.isEmpty()) {
            return new GroupComparison.Verdict(null, null, false, false, null, null,
                    "Todavía no hay ningún precio comparable en este grupo.");
        }

        GroupComparison.Entry cheapest = ranking.getFirst();

        Optional<GroupComparison.Entry> preferred = ranking.stream()
                .filter(GroupComparison.Entry::preferred)
                .findFirst();

        if (preference.isEmpty() || preferred.isEmpty()) {
            String note = preference.isPresent()
                    // La preferencia existe pero su producto no tiene precio: no
                    // se puede aplicar y hay que decirlo, no ignorarla en silencio.
                    ? " Tienes una preferencia marcada, pero ese producto no tiene precio comparable ahora mismo."
                    : "";
            return new GroupComparison.Verdict(cheapest, cheapest, false, false, null, null,
                    "Más barato: " + cheapest.storeName() + " a " + money(cheapest.normalizedUnitPrice())
                            + " €/" + cheapest.unit() + "." + note);
        }

        GroupComparison.Entry chosenByPreference = preferred.get();
        if (chosenByPreference.storeProductId().equals(cheapest.storeProductId())) {
            return new GroupComparison.Verdict(cheapest, cheapest, true, true,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    "Tu preferido es además el más barato: " + cheapest.storeName()
                            + " a " + money(cheapest.normalizedUnitPrice()) + " €/" + cheapest.unit()
                            + ". La prima no hace falta.");
        }

        BigDecimal extra = chosenByPreference.normalizedUnitPrice()
                .subtract(cheapest.normalizedUnitPrice());
        BigDecimal extraPct = cheapest.normalizedUnitPrice().signum() > 0
                ? extra.multiply(BigDecimal.valueOf(100))
                        .divide(cheapest.normalizedUnitPrice(), 2, RoundingMode.HALF_UP)
                : null;

        // El preferido gana si, con la prima descontada, no supera al más barato.
        boolean wins = chosenByPreference.adjustedPrice() != null
                && chosenByPreference.adjustedPrice().compareTo(cheapest.normalizedUnitPrice()) <= 0;

        String unit = cheapest.unit();
        String explanation = wins
                ? "Tu preferido, " + chosenByPreference.storeName() + ", cuesta "
                        + money(extra) + " €/" + unit + " más que el más barato ("
                        + cheapest.storeName() + "), y eso entra dentro de la prima que fijaste. "
                        + "Elección: " + chosenByPreference.storeName() + "."
                : "Tu preferido, " + chosenByPreference.storeName() + ", cuesta "
                        + money(extra) + " €/" + unit + " más que " + cheapest.storeName()
                        + ", por encima de la prima que fijaste. Elección: " + cheapest.storeName() + ".";

        return new GroupComparison.Verdict(
                cheapest,
                wins ? chosenByPreference : cheapest,
                true, wins, extra, extraPct, explanation);
    }

    /**
     * Avisa sobre la CALIDAD del dato, que es la limitación real del enfoque
     * solo-tickets: precios viejos, un solo súper con datos, o miembros fuera de
     * la comparación.
     */
    private String dataWarning(List<GroupComparison.Entry> comparable,
                               List<GroupComparison.Entry> notComparable) {
        List<String> notes = new ArrayList<>();

        if (comparable.size() == 1) {
            notes.add("Solo hay precio en un súper, así que esto no es una comparación todavía.");
        }
        if (!notComparable.isEmpty()) {
            notes.add(notComparable.size() + " producto" + (notComparable.size() == 1 ? "" : "s")
                    + " del grupo se queda" + (notComparable.size() == 1 ? "" : "n")
                    + " fuera de la comparación.");
        }
        if (comparable.size() >= 2) {
            int min = comparable.stream().mapToInt(GroupComparison.Entry::ageDays).min().orElse(0);
            int max = comparable.stream().mapToInt(GroupComparison.Entry::ageDays).max().orElse(0);
            if (max - min > AGE_SPREAD_WARNING_DAYS) {
                notes.add("Los precios que se comparan llevan fechas muy distintas (" + min
                        + " y " + max + " días): puede que la diferencia sea del tiempo, no del súper.");
            }
            if (max > STALE_WARNING_DAYS) {
                notes.add("El precio más antiguo tiene " + max + " días.");
            }
        }
        return notes.isEmpty() ? null : String.join(" ", notes);
    }

    private GroupComparison.GroupInfo toInfo(ComparableGroup group, int memberCount) {
        return new GroupComparison.GroupInfo(
                group.getId(),
                group.getName(),
                group.getComparisonDimension().name(),
                group.getComparisonUnit(),
                group.getCategory() == null ? null : group.getCategory().getId(),
                group.getCategory() == null ? null : group.getCategory().getName(),
                memberCount);
    }

    private String displayName(StoreProduct product) {
        return product.getDisplayName() == null ? product.getCanonicalName() : product.getDisplayName();
    }

    private String money(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
