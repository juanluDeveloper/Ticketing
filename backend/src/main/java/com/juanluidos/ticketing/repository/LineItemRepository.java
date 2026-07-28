package com.juanluidos.ticketing.repository;

import com.juanluidos.ticketing.domain.LineItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LineItemRepository extends JpaRepository<LineItem, Long> {

    List<LineItem> findByTicketIdOrderByLineNoAsc(Long ticketId);

    List<LineItem> findByStoreProductIdOrderByIdAsc(Long storeProductId);
}
