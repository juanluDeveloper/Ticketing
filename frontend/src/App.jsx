import React, { useState } from 'react'
import { api, clearCredentials, hasCredentials, setCredentials } from './api.js'
import UploadView from './UploadView.jsx'
import TicketList from './TicketList.jsx'
import ValidationView from './ValidationView.jsx'
import HistoryView from './HistoryView.jsx'
import ComparatorView from './ComparatorView.jsx'

const TABS = [
  ['tickets', 'Tickets'],
  ['historico', 'Histórico'],
  ['comparador', 'Comparador'],
]

export default function App() {
  const [authed, setAuthed] = useState(hasCredentials())
  const [tab, setTab] = useState('tickets')
  const [openTicketId, setOpenTicketId] = useState(null)
  const [reloadToken, setReloadToken] = useState(0)

  if (!authed) return <Login onDone={() => setAuthed(true)} />

  return (
    <div className="app">
      <header>
        {/* La cabecera es pegajosa: en la validación se baja por 22 líneas y
            perder las pestañas obliga a subir del todo para cambiar de vista. */}
        <div className="headerinner">
          <div className="brand">
            <img src="/icon.svg" width="22" height="22" alt="" />
            <h1>Tickets de la compra</h1>
          </div>
          <nav>
            {TABS.map(([id, label]) => (
              <button
                key={id}
                className={tab === id ? 'tab active' : 'tab'}
                aria-current={tab === id ? 'page' : undefined}
                onClick={() => {
                  setTab(id)
                  setOpenTicketId(null)
                }}
              >
                {label}
              </button>
            ))}
          </nav>
          <button
            className="link"
            onClick={() => {
              clearCredentials()
              setAuthed(false)
            }}
          >
            Salir
          </button>
        </div>
      </header>

      <main>
        {tab === 'historico' && <HistoryView />}
        {tab === 'comparador' && <ComparatorView />}

        {tab === 'tickets' &&
          (openTicketId ? (
            <ValidationView
              ticketId={openTicketId}
              onOpenTicket={setOpenTicketId}
              onClose={() => {
                setOpenTicketId(null)
                setReloadToken((n) => n + 1)
              }}
            />
          ) : (
            <>
              <UploadView onUploaded={(id) => setOpenTicketId(id)} />
              <TicketList reloadToken={reloadToken} onOpen={setOpenTicketId} />
            </>
          ))}
      </main>
    </div>
  )
}

function Login({ onDone }) {
  const [username, setUsername] = useState('juanluidos')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)

  async function submit(event) {
    event.preventDefault()
    setError(null)
    setCredentials(username, password)
    try {
      await api.listTickets()
      onDone()
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <div className="loginwrap">
      <form className="login" onSubmit={submit}>
        <div className="loginbrand">
          <img src="/icon.svg" width="26" height="26" alt="" />
          <h1>Tickets de la compra</h1>
        </div>
        <div className="loginbox">
          <label>
            Usuario
            <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
          </label>
          <label>
            Contraseña
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          </label>
          {error && <p className="error small">{error}</p>}
          <button className="primary" type="submit">Entrar</button>
        </div>
        <p className="hint">Un solo usuario, autenticación básica. No hay registro ni recuperación de contraseña.</p>
      </form>
    </div>
  )
}
