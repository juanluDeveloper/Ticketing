package com.juanluidos.ticketing.api;

import com.juanluidos.ticketing.comparison.ComparisonService;
import com.juanluidos.ticketing.comparison.GroupComparison;
import com.juanluidos.ticketing.comparison.GroupService;
import com.juanluidos.ticketing.domain.Dimension;
import com.juanluidos.ticketing.domain.MarginType;
import com.juanluidos.ticketing.repository.AppUserRepository;
import com.juanluidos.ticketing.repository.ComparableGroupRepository;
import com.juanluidos.ticketing.repository.StoreProductRepository;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/groups")
public class ComparisonController {

    private final GroupService groupService;
    private final ComparisonService comparison;
    private final ComparableGroupRepository groups;
    private final StoreProductRepository products;
    private final AppUserRepository users;

    public ComparisonController(GroupService groupService, ComparisonService comparison,
                                ComparableGroupRepository groups, StoreProductRepository products,
                                AppUserRepository users) {
        this.groupService = groupService;
        this.comparison = comparison;
        this.groups = groups;
        this.products = products;
        this.users = users;
    }

    public record CreateGroup(String name, Dimension dimension, Long categoryId) {
    }

    public record AddMember(Long storeProductId) {
    }

    public record SetPreference(Long storeProductId, MarginType marginType,
                                BigDecimal marginValue, String note) {
    }

    public record GroupRow(Long id, String name, String comparisonUnit, int memberCount,
                           int comparableCount, String cheapestStore, BigDecimal cheapestPrice,
                           boolean hasPreference) {
    }

    public record UngroupedProduct(Long id, String storeCode, String name, BigDecimal packageSize,
                                   String packageUnit, String dimension, String soldBy) {
    }

    @GetMapping
    public List<GroupRow> list(Principal principal) {
        Long userId = userId(principal);
        return groups.findAll().stream()
                .map(g -> {
                    GroupComparison c = comparison.compare(g.getId(), userId);
                    var cheapest = c.verdict().cheapest();
                    return new GroupRow(
                            g.getId(), g.getName(), g.getComparisonUnit(),
                            c.group().memberCount(), c.ranking().size(),
                            cheapest == null ? null : cheapest.storeName(),
                            cheapest == null ? null : cheapest.normalizedUnitPrice(),
                            c.verdict().preferenceApplied());
                })
                .sorted(Comparator.comparing(GroupRow::name))
                .toList();
    }

    @PostMapping
    public GroupRow create(@RequestBody CreateGroup request, Principal principal) {
        var group = groupService.create(request.name(), request.dimension(), request.categoryId());
        return list(principal).stream()
                .filter(r -> r.id().equals(group.getId()))
                .findFirst()
                .orElseThrow();
    }

    @GetMapping("/{id}/comparison")
    public GroupComparison comparison(@PathVariable Long id, Principal principal) {
        return comparison.compare(id, userId(principal));
    }

    @GetMapping("/{id}/suggestions")
    public List<GroupService.Suggestion> suggestions(@PathVariable Long id) {
        return groupService.suggestMembers(id);
    }

    @PostMapping("/{id}/members")
    public GroupComparison addMember(@PathVariable Long id, @RequestBody AddMember request,
                                     Principal principal) {
        groupService.addMember(id, request.storeProductId());
        return comparison.compare(id, userId(principal));
    }

    @DeleteMapping("/{id}/members/{storeProductId}")
    public GroupComparison removeMember(@PathVariable Long id, @PathVariable Long storeProductId,
                                        Principal principal) {
        groupService.removeMember(storeProductId);
        return comparison.compare(id, userId(principal));
    }

    @PutMapping("/{id}/preference")
    public GroupComparison setPreference(@PathVariable Long id, @RequestBody SetPreference request,
                                         Principal principal) {
        groupService.setPreference(userId(principal), id, request.storeProductId(),
                request.marginType(), request.marginValue(), request.note());
        return comparison.compare(id, userId(principal));
    }

    @DeleteMapping("/{id}/preference")
    public GroupComparison clearPreference(@PathVariable Long id, Principal principal) {
        groupService.clearPreference(userId(principal), id);
        return comparison.compare(id, userId(principal));
    }

    /** Para montar grupos nuevos desde productos que aún no están en ninguno. */
    @GetMapping("/ungrouped")
    public List<UngroupedProduct> ungrouped() {
        return products.findByComparableGroupIdIsNull().stream()
                .map(p -> new UngroupedProduct(
                        p.getId(),
                        p.getStore().getCode(),
                        p.getDisplayName() == null ? p.getCanonicalName() : p.getDisplayName(),
                        p.getPackageSize(), p.getPackageUnit(),
                        p.getDimension() == null ? null : p.getDimension().name(),
                        p.getSoldBy() == null ? null : p.getSoldBy().name()))
                .sorted(Comparator.comparing(UngroupedProduct::name))
                .toList();
    }

    private Long userId(Principal principal) {
        return users.findByUsername(principal.getName()).orElseThrow().getId();
    }
}
