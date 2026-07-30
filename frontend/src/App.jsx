import React, { useEffect, useState } from 'react'
import { api, clearCredentials, hasCredentials, setCredentials } from './api.js'
import UploadView from './UploadView.jsx'
import TicketList from './TicketList.jsx'
import ValidationView from './ValidationView.jsx'
import HistoryView from './HistoryView.jsx'

export default function App() {
  const [authed, setAuthed] = useState(hasCredentials())
  const [tab, setTab] = useState('tickets')
  const [openTicketId, setOpenTicketId] = useState(null)
  const [reloadToken, setReloadToken] = useState(0)

  if (!authed) return <Login onDone={() => setAuthed(true)} />

  return (
    <div className="app">
      <header>
        <h1>Tickets de la compra</h1>
        <nav>
          <button
            className={tab === 'tickets' ? 'tab active' : 'tab'}
            onClick={() => {
              setTab('tickets')
              setOpenTicketId(null)
            }}
          >
            Tickets
          </button>
          <button
            className={tab === 'historico' ? 'tab active' : 'tab'}
            onClick={() => {
              setTab('historico')
              setOpenTicketId(null)
            }}
          >
            Histórico
          </button>
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
      </header>

      {tab === 'historico' && <HistoryView />}

      {tab === 'tickets' &&
        (openTicketId ? (
          <ValidationView
            ticketId={openTicketId}
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
    <form className="login" onSubmit={submit}>
      <h1>Tickets</h1>
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
      {error && <p className="error">{error}</p>}
      <button type="submit">Entrar</button>
    </form>
  )
}
