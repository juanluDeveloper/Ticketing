package com.juanluidos.ticketing.domain;

/** Estado de un hallazgo. ACCEPTED = la persona lo ha visto y decide seguir igual. */
public enum IssueStatus {
    OPEN,
    RESOLVED,
    ACCEPTED
}
