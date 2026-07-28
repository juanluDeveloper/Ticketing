package com.juanluidos.ticketing.repository;

import com.juanluidos.ticketing.domain.UserProductPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserProductPreferenceRepository extends JpaRepository<UserProductPreference, Long> {

    Optional<UserProductPreference> findByUserIdAndComparableGroupId(Long userId, Long comparableGroupId);

    List<UserProductPreference> findByUserId(Long userId);
}
