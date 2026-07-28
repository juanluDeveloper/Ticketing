package com.juanluidos.ticketing.repository;

import com.juanluidos.ticketing.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByCode(String code);

    List<Category> findByParentIsNullOrderByName();

    List<Category> findByParentIdOrderByName(Long parentId);
}
