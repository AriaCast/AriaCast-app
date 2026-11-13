// The access token comes from the link the app shows (#t=...) and is kept in localStorage,
// so the fragment never reaches server logs and the page can be bookmarked without it.
const KEY = 'ariacast-token'

export function readToken() {
  const m = location.hash.match(/t=([A-Za-z0-9_-]+)/)
  if (m) {
    try { localStorage.setItem(KEY, m[1]) } catch (e) { /* storage blocked */ }
    history.replaceState(null, '', location.pathname)
    return m[1]
  }
  try { return localStorage.getItem(KEY) } catch (e) { return null }
}

export async function api(token, path, body) {
  const res = await fetch('/api/' + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (res.status === 401) throw new Error('unauthorized')
  if (!res.ok && res.status !== 409) throw new Error('HTTP ' + res.status)
  return path === 'artwork' ? res.blob() : res.json()
}
