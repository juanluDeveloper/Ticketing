package com.juanluidos.ticketing.comparison;

import com.juanluidos.ticketing.domain.*;
import com.juanluidos.ticketing.matching.TextNormalizer;
import com.juanluidos.ticketing.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Alta y composición de grupos comparables.
 *
 * <p>El agrupado es asistido, no automático: se sugiere por categoría y parecido
 * de nombre, y la persona confirma. Un matcher que junte solo "MANGO S/AZ
 * AÑADIDO" con "MANGO" fresco produce un comparador que miente con seguridad.
 */
@Service
public class GroupService {

    private static final double SUGGESTION_THRESHOLD = 0.25;

    private final ComparableGroupRepository groups;
    private final StoreProductRepository products;
    private final CategoryRepository categories;
    private final UserProductPreferenceRepository preferences;
    private final AppUserRepository users;

    public GroupService(ComparableGroupRepository groups, StoreProductRepository products,
                        CategoryRepository categories, UserProductPreferenceRepository preferences,
                        AppUserRepository users) {
        this.groups = groups;
        this.products = products;
        this.categories = categories;
        this.preferences = preferences;
        this.users = users;
    }

    public record Suggestion(
            Long storeProductId,
            String storeCode,
            String storeName,
            String name,
            BigDecimal packageSize,
            String packageUnit,
            String soldBy,
            Double similarity,
            boolean sameCategory,
            /** Ya está en otro grupo: moverlo aquí lo sacaría de aquel. */
            Long currentGroupId
    ) {
    }

    @Transactional
    public ComparableGroup create(String name, Dimension dimension, Long categoryId) {
        ComparableGroup group = new ComparableGroup();
        group.setName(name);
        group.setComparisonDimension(dimension);
        // La unidad no se elige: la fija la dimensión, para que serie de precios,
        // ranking y prima de preferencia hablen todos la misma.
        group.setComparisonUnit(dimension.getCanonicalUnit());
        if (categoryId != null) {
            group.setCategory(categories.findById(categoryId).orElse(null));
        }
        return groups.save(group);
    }

    @Transactional
    public void addMember(Long groupId, Long storeProductId) {
        ComparableGroup group = groups.findById(groupId).orElseThrow();
        StoreProduct product = products.findById(storeProductId).orElseThrow();

        // Se deja pasar dimensión nula (producto aún sin tamaño): entrará en la
        // comparación en cuanto se rellene. Lo que no se deja pasar es una
        // dimensión distinta, que daría un ranking sin sentido.
        if (product.getDimension() != null && product.getDimension() != group.getComparisonDimension()) {
            throw new IllegalArgumentException(
                    "\"" + displayName(product) + "\" se mide en " + product.getDimension()
                            + " y el grupo compara en " + group.getComparisonDimension()
                            + ". No son comparables entre sí.");
        }

        product.setComparableGroup(group);
        products.save(product);
    }

    @Transactional
    public void removeMember(Long storeProductId) {
        StoreProduct product = products.findById(storeProductId).orElseThrow();
        Long groupId = product.getComparableGroup() == null ? null : product.getComparableGroup().getId();
        product.setComparableGroup(null);
        products.save(product);

        // Si el producto que se va era el preferido de alguien, esa preferencia
        // se queda apuntando fuera del grupo: se retira en vez de dejarla rota.
        if (groupId != null) {
            preferences.findAll().stream()
                    .filter(p -> p.getComparableGroup().getId().equals(groupId))
                    .filter(p -> p.getPreferredStoreProduct().getId().equals(storeProductId))
                    .forEach(preferences::delete);
        }
    }

    @Transactional(readOnly = true)
    public List<Suggestion> suggestMembers(Long groupId) {
        ComparableGroup group = groups.findById(groupId).orElseThrow();
        List<StoreProduct> members = products.findByComparableGroupId(groupId);

        // Se busca por el nombre del grupo y también por el de cada miembro: el
        // nombre del grupo suele ser limpio ("Leche entera 1 L") y los de los
        // tickets abreviados ("LECHE ENTERA ACORES"), así que por separado
        // encuentran cosas distintas.
        java.util.LinkedHashMap<Long, Suggestion> found = new java.util.LinkedHashMap<>();
        java.util.List<String> needles = new java.util.ArrayList<>();
        needles.add(TextNormalizer.normalize(group.getName()));
        members.forEach(m -> needles.add(TextNormalizer.normalize(displayName(m))));

        for (String needle : needles) {
            if (needle == null || needle.isBlank()) {
                continue;
            }
            products.findCandidatesForGroup(groupId, needle,
                            group.getComparisonDimension().name(), SUGGESTION_THRESHOLD, 10)
                    .forEach(hit -> products.findById(hit.getStoreProductId())
                            .filter(p -> p.getComparableGroup() == null
                                    || !p.getComparableGroup().getId().equals(groupId))
                            .ifPresent(p -> found.merge(p.getId(),
                                    toSuggestion(p, hit.getSimilarity(), group),
                                    (a, b) -> a.similarity() >= b.similarity() ? a : b)));
        }

        return found.values().stream()
                // Primero lo de la misma categoría, luego por parecido de nombre.
                .sorted(Comparator.comparing(Suggestion::sameCategory).reversed()
                        .thenComparing(Comparator.comparingDouble(Suggestion::similarity).reversed()))
                .toList();
    }

    @Transactional
    public UserProductPreference setPreference(Long userId, Long groupId, Long storeProductId,
                                               MarginType marginType, BigDecimal marginValue,
                                               String note) {
        StoreProduct product = products.findById(storeProductId).orElseThrow();
        if (product.getComparableGroup() == null
                || !product.getComparableGroup().getId().equals(groupId)) {
            throw new IllegalArgumentException(
                    "Ese producto no está en este grupo, así que no puede ser el preferido.");
        }

        UserProductPreference preference = preferences
                .findByUserIdAndComparableGroupId(userId, groupId)
                .orElseGet(UserProductPreference::new);

        preference.setUser(users.findById(userId).orElseThrow());
        preference.setComparableGroup(groups.findById(groupId).orElseThrow());
        preference.setPreferredStoreProduct(product);
        preference.setMarginType(marginType == null ? MarginType.ABS : marginType);
        preference.setMarginValue(marginValue == null ? BigDecimal.ZERO : marginValue);
        preference.setNote(note);
        return preferences.save(preference);
    }

    @Transactional
    public void clearPreference(Long userId, Long groupId) {
        preferences.findByUserIdAndComparableGroupId(userId, groupId)
                .ifPresent(preferences::delete);
    }

    @Transactional(readOnly = true)
    public Optional<UserProductPreference> preferenceOf(Long userId, Long groupId) {
        return preferences.findByUserIdAndComparableGroupId(userId, groupId);
    }

    private Suggestion toSuggestion(StoreProduct product, Double similarity, ComparableGroup group) {
        boolean sameCategory = group.getCategory() != null
                && product.getCategory() != null
                && group.getCategory().getId().equals(product.getCategory().getId());

        return new Suggestion(
                product.getId(),
                product.getStore().getCode(),
                product.getStore().getName(),
                displayName(product),
                product.getPackageSize(),
                product.getPackageUnit(),
                product.getSoldBy() == null ? null : product.getSoldBy().name(),
                similarity,
                sameCategory,
                product.getComparableGroup() == null ? null : product.getComparableGroup().getId());
    }

    private String displayName(StoreProduct product) {
        return product.getDisplayName() == null ? product.getCanonicalName() : product.getDisplayName();
    }
}
