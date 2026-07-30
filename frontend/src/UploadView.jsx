import React, { useState } from 'react'
import { api } from './api.js'

export default function UploadView({ onUploaded }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)

  async function pick(event) {
    const file = event.target.files?.[0]
    if (!file) return
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const result = await api.upload(file)
      if (result.sameImageAs?.length) {
        setNotice(`Ojo: esta misma foto ya se subió en el ticket #${result.sameImageAs.join(', #')}.`)
      }
      onUploaded(result.ticketId)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
      event.target.value = ''
    }
  }

  return (
    <section className="card upload">
      <h2>Subir ticket</h2>
      <p className="muted">
        La extracción va por detrás y puede tardar minutos: en cuanto se sube, el ticket queda en
        cola y se puede seguir a lo otro.
      </p>
      {/* capture abre la cámara directamente en el móvil */}
      <label className="filebutton">
        {busy ? 'Subiendo…' : 'Hacer foto o elegir imagen'}
        <input type="file" accept="image/*" capture="environment" onChange={pick} disabled={busy} />
      </label>
      {notice && <p className="warn">{notice}</p>}
      {error && <p className="error">{error}</p>}
    </section>
  )
}
