package com.juanluidos.ticketing.repository;

import com.juanluidos.ticketing.domain.TicketCheckResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketCheckResultRepository extends JpaRepository<TicketCheckResult, Long> {

    List<TicketCheckResult> findByTicketId(Long ticketId);

    void deleteByTicketId(Long ticketId);
}
