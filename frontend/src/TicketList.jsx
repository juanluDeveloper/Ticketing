import React, { useEffect, useMemo, useState } from 'react'
import { api } from './api.js'

const STATUS_LABEL = {
  UPLOADED: 'en cola',
  EXTRACTING: 'extrayendo…',
  EXTRACTED: 'pendiente de revisar',
  VALIDATED: 'validado',
  EXTRACTION_ERROR: 'error',
}

const SORT_VALUE = {
  id: (ticket) => ticket.id,
  status: (ticket) => STATUS_LABEL[ticket.status] ?? ticket.status,
  store: (ticket) => ticket.storeName,
  purchasedAt: (ticket) => ticket.purchasedAt,
  receiptNumber: (ticket) => ticket.receiptNumber,
  total: (ticket) => ticket.total,
  lineCount: (ticket) => ticket.lineCount,
}

export default function TicketList({ reloadToken, onOpen }) {
  const [tickets, setTickets] = useState([])
  const [error, setError] = useState(null)
  const [refresh, setRefresh] = useState(0)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('')
  const [sort, setSort] = useState({ key: 'purchasedAt', direction: 'desc' })

  /**
   * Borrar es lo que libera el número de recibo. Sin esto, una compra
   * fotografiada mal queda inservible: la deduplicación impide volver a subirla
   * y no había forma de quitar la mala.
   */
  async function remove(ticket) {
    const label = `#${ticket.id} · ${ticket.storeName ?? 'ticket'} · ${ticket.total ?? '—'} €`
    if (!window.confirm(`¿Borrar el ticket ${label}?`)) return

    setError(null)
    try {
      await api.deleteTicket(ticket.id)
      setRefresh((n) => n + 1)
    } catch (e) {
      // El backend rechaza los validados sin confirmación explícita, porque el
      // borrado se lleva por delante los precios que aportaron al histórico.
      if (e.message.includes('histórico')) {
        if (window.confirm(`${e.message}\n\n¿Borrarlo de todas formas?`)) {
          try {
            await api.deleteTicket(ticket.id, true)
            setRefresh((n) => n + 1)
          } catch (forced) {
            setError(forced.message)
          }
        }
        return
      }
      setError(e.message)
    }
  }

  useEffect(() => {
    let cancelled = false

    async function load() {
      try {
        const data = await api.listTickets()
        if (!cancelled) setTickets(data)
      } catch (e) {
        if (!cancelled) setError(e.message)
      }
    }

    load()
    // Mientras haya algo en la GPU, se refresca solo: no hay push, y el usuario
    // no debería tener que recargar para ver que ya terminó.
    const timer = setInterval(load, 4000)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [reloadToken, refresh])

  const visibleTickets = useMemo(() => {
    const needle = normalize(query)
    const filtered = tickets.filter((ticket) => {
      if (status && ticket.status !== status) return false
      if (!needle) return true

      const searchable = [
        ticket.id,
        `#${ticket.id}`,
        ticket.storeName,
        ticket.storeCode,
        ticket.receiptNumber,
        ticket.purchasedAt,
        formatDate(ticket.purchasedAt),
        ticket.total,
        ticket.total == null ? null : fmt(ticket.total),
        ticket.lineCount,
        STATUS_LABEL[ticket.status] ?? ticket.status,
        ticket.extractionError,
      ]
      return searchable.some((value) => normalize(value).includes(needle))
    })

    const valueOf = SORT_VALUE[sort.key]
    return [...filtered].sort((a, b) => compare(valueOf(a), valueOf(b), sort.direction))
  }, [query, sort, status, tickets])

  function changeSort(key) {
    setSort((current) => ({
      key,
      direction: current.key === key && current.direction === 'asc' ? 'desc' : 'asc',
    }))
  }

  function clearFilters() {
    setQuery('')
    setStatus('')
  }

  if (error && tickets.length === 0) return <p className="error">{error}</p>

  const filtering = Boolean(query || status)

  return (
    <section className="card ticket-list-card">
      <div className="ticket-list-head">
        <h2>Tickets</h2>
        <span className="muted small">
          {visibleTickets.length === tickets.length
            ? `${tickets.length} ${tickets.length === 1 ? 'ticket' : 'tickets'}`
            : `${visibleTickets.length} de ${tickets.length} tickets`}
        </span>
      </div>

      {tickets.length > 0 && (
        <div className="ticket-filters">
          <label className="ticket-search">
            Buscar
            <input
              type="search"
              placeholder="ID, nº de recibo, súper, fecha, importe…"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </label>
          <label>
            Estado
            <select value={status} onChange={(event) => setStatus(event.target.value)}>
              <option value="">Todos</option>
              {Object.entries(STATUS_LABEL).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
          </label>
          {filtering && (
            <button className="link" onClick={clearFilters}>Limpiar filtros</button>
          )}
        </div>
      )}

      {error && <p className="error">{error}</p>}
      {tickets.length === 0 && <p className="muted">Todavía no hay ninguno.</p>}

      {tickets.length > 0 && visibleTickets.length === 0 && (
        <div className="empty ticket-empty">
          <h3>Ningún ticket coincide</h3>
          <p className="muted">Prueba con otro texto o quita el filtro de estado.</p>
          <button onClick={clearFilters}>Limpiar filtros</button>
        </div>
      )}

      {visibleTickets.length > 0 && (
        <div className="ticket-table-wrap">
          <table className="ticket-table">
            <thead>
              <tr>
                <SortHeader column="id" label="ID" sort={sort} onSort={changeSort} />
                <SortHeader column="status" label="Estado" sort={sort} onSort={changeSort} />
                <SortHeader column="store" label="Súper" sort={sort} onSort={changeSort} />
                <SortHeader column="purchasedAt" label="Fecha" sort={sort} onSort={changeSort} />
                <SortHeader column="receiptNumber" label="N.º recibo" sort={sort} onSort={changeSort} />
                <SortHeader column="total" label="Total" sort={sort} onSort={changeSort} right />
                <SortHeader column="lineCount" label="Líneas" sort={sort} onSort={changeSort} right />
                <th className="ticket-actions-head">Acciones</th>
              </tr>
            </thead>
            <tbody>
              {visibleTickets.map((ticket) => (
                <tr key={ticket.id} className="ticket-data-row">
                  <td data-label="ID" className="ticket-cell-id">
                    <button className="link ticket-id" onClick={() => onOpen(ticket.id)}>
                      #{ticket.id}
                    </button>
                  </td>
                  <td data-label="Estado" className="ticket-cell-status">
                    <span className={`badge ${ticket.status.toLowerCase()}`}>
                      {STATUS_LABEL[ticket.status] ?? ticket.status}
                    </span>
                  </td>
                  <td data-label="Súper" className="ticket-cell-store">
                    <button className="link ticket-store" onClick={() => onOpen(ticket.id)}>
                      {ticket.storeName ?? 'súper sin identificar'}
                    </button>
                    {ticket.extractionError && (
                      <TicketError message={ticket.extractionError} onOpen={onOpen} />
                    )}
                  </td>
                  <td data-label="Fecha" className="ticket-date">
                    {formatDate(ticket.purchasedAt)}
                  </td>
                  <td data-label="N.º recibo" className="ticket-receipt">
                    {ticket.receiptNumber ?? '—'}
                  </td>
                  <td data-label="Total" className="right ticket-total">
                    {ticket.total != null ? `${fmt(ticket.total)} €` : '—'}
                  </td>
                  <td data-label="Líneas" className="right">{ticket.lineCount}</td>
                  <td data-label="Acciones" className="ticket-actions">
                    <button className="link" onClick={() => onOpen(ticket.id)}>Abrir</button>
                    <button
                      className="link danger"
                      title={`Borrar el ticket #${ticket.id}`}
                      disabled={ticket.status === 'EXTRACTING'}
                      onClick={() => remove(ticket)}
                    >
                      Borrar
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function SortHeader({ column, label, sort, onSort, right = false }) {
  const active = sort.key === column
  const direction = active ? sort.direction : null
  return (
    <th className={right ? 'right' : ''} aria-sort={active ? `${direction}ending` : 'none'}>
      <button
        className={`sort-button ${active ? 'active' : ''}`}
        onClick={() => onSort(column)}
        title={`Ordenar por ${label.toLowerCase()}`}
      >
        {label}
        <span aria-hidden="true">{direction === 'asc' ? '↑' : direction === 'desc' ? '↓' : '↕'}</span>
      </button>
    </th>
  )
}

/** Convierte "ticket #20" en un acceso directo al duplicado ya registrado. */
function TicketError({ message, onOpen }) {
  const referencedId = message.match(/ticket\s+#(\d+)/i)?.[1]
  return (
    <span className="ticket-row-error">
      {message}
      {referencedId && (
        <button className="link" onClick={() => onOpen(Number(referencedId))}>
          Abrir #{referencedId}
        </button>
      )}
    </span>
  )
}

function compare(a, b, direction) {
  // Los datos desconocidos permanecen abajo tanto en ascendente como en
  // descendente: un guion no debería ocupar la primera fila de una ordenación.
  if (a == null && b == null) return 0
  if (a == null) return 1
  if (b == null) return -1

  const result = typeof a === 'number' && typeof b === 'number'
    ? a - b
    : String(a).localeCompare(String(b), 'es', { numeric: true, sensitivity: 'base' })
  return direction === 'asc' ? result : -result
}

function normalize(value) {
  return String(value ?? '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .trim()
}

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('es-ES', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(value))
}

function fmt(value) {
  return Number(value).toFixed(2).replace('.', ',')
}
