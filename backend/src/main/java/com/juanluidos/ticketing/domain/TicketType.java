package com.juanluidos.ticketing.domain;

/** Discriminador para que la ingesta sirva a los dos verticales sin acoplar esquemas. */
public enum TicketType {
    SUPERMARKET,
    RESTAURANT
}
