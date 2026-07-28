package com.juanluidos.ticketing.repository;

import com.juanluidos.ticketing.domain.TicketTaxSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketTaxSummaryRepository extends JpaRepository<TicketTaxSummary, Long> {

    List<TicketTaxSummary> findByTicketId(Long ticketId);
}
