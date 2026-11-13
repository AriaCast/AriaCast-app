import React, { useEffect, useRef, useState } from 'react'
import { api, readToken } from './api.js'

const PLATFORM = { AirPlay2: 'AirPlay 2', 'Google Cast': 'Cast', AriaCast: 'AriaCast' }
const label = (p) => PLATFORM[p] || p || 'AriaCast'

export default function App() {
  const [token] = useState(readToken)
  const [state, setState] = useState(null)
  const [error, setError] = useState(null)
  const [art, setArt] = useState(null)
  const [volume, setVolume] = useState(null) // set while dragging
  const artKey = useRef('')

  useEffect(() => {
    if (!token) return
    let stop = false
    const poll = async () => {
      try {
        const s = await api(token, 'state')
        if (stop) return
        setState(s); setError(null)
        const key = `${s.track.title}|${s.track.artist}|${s.track.hasArtwork}`
        if (key !== artKey.current) {
          artKey.current = key
          if (s.track.hasArtwork) {
            const blob = await api(token, 'artwork').catch(() => null)
            setArt((old) => { if (old) URL.revokeObjectURL(old); return blob ? URL.createObjectURL(blob) : null })
          } else setArt(null)
        }
      } catch (e) {
        if (!stop) setError(e.message)
      }
    }
    poll()
    const id = setInterval(poll, 1500)
    return () => { stop = true; clearInterval(id) }
  }, [token])

  if (!token) return <Shell><p className="muted">Open this page from the link or QR code in AriaCast (menu → Web remote).</p></Shell>
  if (error === 'unauthorized') return <Shell><p className="muted">This link is no longer valid. Get a new one from AriaCast (menu → Web remote).</p></Shell>
  if (!state) return <Shell><p className="muted">{error ? `Can't reach the phone (${error}).` : 'Connecting…'}</p></Shell>

  const casting = state.state === 'CASTING' || state.state === 'CONNECTING'
  const send = (path, body) => api(token, path, body).catch((e) => setError(e.message))

  return (
    <Shell>
      <section className="card now">
        <div className="art" style={art ? { backgroundImage: `url(${art})` } : undefined}>{art ? null : '♪'}</div>
        <div className="meta">
          <div className="status">{casting ? `Casting to ${state.active.map((d) => d.name).join(', ')}` : 'Not casting'}</div>
          <h1>{state.track.title || (casting ? 'Unknown track' : '—')}</h1>
          <p className="muted">{[state.track.artist, state.track.album].filter(Boolean).join(' · ')}</p>
        </div>
        {casting && (
          <>
            <div className="controls">
              <button aria-label="Previous" onClick={() => send('media', { command: 'previous' })}>⏮</button>
              <button aria-label="Play or pause" className="big" onClick={() => send('media', { command: 'toggle' })}>{state.track.playing ? '⏸' : '▶'}</button>
              <button aria-label="Next" onClick={() => send('media', { command: 'next' })}>⏭</button>
            </div>
            <label className="volume">
              <span>Volume</span>
              <input type="range" min="0" max="100" value={volume ?? state.volume}
                onChange={(e) => setVolume(Number(e.target.value))}
                onPointerUp={() => { if (volume != null) { send('volume', { percent: volume }); setVolume(null) } }}
                onKeyUp={() => { if (volume != null) { send('volume', { percent: volume }); setVolume(null) } }} />
              <span className="num">{volume ?? state.volume}</span>
            </label>
            <button className="stop" onClick={() => send('stop', {})}>Stop casting</button>
          </>
        )}
      </section>

      {casting && (
        <section className="card">
          <h2>Speakers</h2>
          {state.active.map((d) => (
            <div className="row" key={d.platform + d.host}>
              <span className="dot on" /> <span className="grow">{d.name} <small className="muted">{label(d.platform)}</small></span>
              {state.active.length > 1 && <button className="ghost" onClick={() => send('speaker-remove', { host: d.host, platform: d.platform })}>Remove</button>}
            </div>
          ))}
          {state.speakers.map((d) => (
            <div className="row" key={d.platform + d.host}>
              <span className="dot" /> <span className="grow">{d.name} <small className="muted">{label(d.platform)}</small></span>
              <button className="ghost" onClick={() => send('speaker-add', { host: d.host, platform: d.platform })}>Add</button>
            </div>
          ))}
        </section>
      )}

      <section className="card">
        <h2>Receiver</h2>
        <div className="row">
          <span className="grow">
            {state.receiver.running
              ? (state.receiver.sender ? `Playing ${[state.receiver.title, state.receiver.artist].filter(Boolean).join(' · ') || 'from ' + state.receiver.sender}` : `Ready as “${state.receiver.name}”`)
              : 'Off'}
          </span>
          <button className="ghost" onClick={() => send('receiver', { on: !state.receiver.running })}>{state.receiver.running ? 'Turn off' : 'Turn on'}</button>
        </div>
      </section>
      {error && <p className="error">{error}</p>}
    </Shell>
  )
}

function Shell({ children }) {
  return (
    <main>
      <header><span className="logo">AriaCast</span><span className="muted">Remote</span></header>
      {children}
    </main>
  )
}
