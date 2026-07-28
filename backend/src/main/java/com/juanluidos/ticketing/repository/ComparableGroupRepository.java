package com.juanluidos.ticketing.repository;

import com.juanluidos.ticketing.domain.ComparableGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComparableGroupRepository extends JpaRepository<ComparableGroup, Long> {

    List<ComparableGroup> findByCategoryId(Long categoryId);
}
