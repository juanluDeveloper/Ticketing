import React, { useEffect, useState } from 'react'
import { api } from './api.js'

const STATUS_LABEL = {
  UPLOADED: 'en cola',
  EXTRACTING: 'extrayendo…',
  EXTRACTED: 'pendiente de revisar',
  VALIDATED: 'validado',
  EXTRACTION_ERROR: 'error',
}

export default function TicketList({ reloadToken, onOpen }) {
  const [tickets, setTickets] = useState([])
  const [error, setError] = useState(null)

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
  }, [reloadToken])

  if (error) return <p className="error">{error}</p>

  return (
    <section className="card">
      <h2>Tickets</h2>
      {tickets.length === 0 && <p className="muted">Todavía no hay ninguno.</p>}
      <ul className="tickets">
        {tickets.map((t) => (
          <li key={t.id}>
            <button className="ticketrow" onClick={() => onOpen(t.id)}>
              <span className={`badge ${t.status.toLowerCase()}`}>
                {STATUS_LABEL[t.status] ?? t.status}
              </span>
              <span className="store">{t.storeName ?? 'súper sin identificar'}</span>
              <span className="date">
                {t.purchasedAt ? t.purchasedAt.replace('T', ' ').slice(0, 16) : '—'}
              </span>
              <span className="total">{t.total != null ? `${fmt(t.total)} €` : '—'}</span>
              <span className="muted">{t.lineCount} líneas</span>
            </button>
            {t.extractionError && <p className="error small">{t.extractionError}</p>}
          </li>
        ))}
      </ul>
    </section>
  )
}

function fmt(value) {
  return Number(value).toFixed(2).replace('.', ',')
}
