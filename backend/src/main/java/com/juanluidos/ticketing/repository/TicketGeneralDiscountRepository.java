package com.juanluidos.ticketing.repository;

import com.juanluidos.ticketing.domain.TicketGeneralDiscount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketGeneralDiscountRepository extends JpaRepository<TicketGeneralDiscount, Long> {

    List<TicketGeneralDiscount> findByTicketIdOrderByPositionAsc(Long ticketId);
}
