import { randomUUID } from 'node:crypto'

const required = (name) => {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`Missing required environment variable ${name}`)
  return value
}

const apiBaseUrl = (process.env.LIVE_API_BASE_URL ?? 'http://127.0.0.1:28080').replace(/\/$/, '')
const merchantCode = process.env.LIVE_TEST_MERCHANT_CODE?.trim() || 'DEMO_MERCHANT'
const username = process.env.LIVE_TEST_USERNAME?.trim() || 'csr'
const password = required('LIVE_TEST_PASSWORD')

const loginResponse = await fetch(`${apiBaseUrl}/api/v1/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ merchantCode, username, password }),
})
if (!loginResponse.ok) throw new Error(`Login failed with HTTP ${loginResponse.status}`)
const loginEnvelope = await loginResponse.json()
const session = loginEnvelope?.data
if (loginEnvelope?.code !== 'OK' || !session?.accessToken) throw new Error('Login returned an invalid response')
const headers = { Authorization: `Bearer ${session.accessToken}` }

const mode = process.argv[2]
if (mode === 'seed') {
  const response = await fetch(`${apiBaseUrl}/api/v1/conversations`, {
    method: 'POST',
    headers: { ...headers, 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: '合成故障恢复演练' }),
  })
  if (!response.ok) throw new Error(`Could not create recovery conversation: HTTP ${response.status}`)
  const envelope = await response.json()
  if (envelope?.code !== 'OK') throw new Error('Conversation creation returned an invalid response')
  process.stdout.write(`${JSON.stringify({
    tenantId: session.tenantId,
    actorId: session.actorId,
    conversationId: envelope.data.conversationId,
    runId: randomUUID(),
    requestId: randomUUID(),
  })}\n`)
} else if (mode === 'verify') {
  const runId = process.argv[3]
  if (!runId) throw new Error('Pass the interrupted run id as the third argument')
  const response = await fetch(`${apiBaseUrl}/api/v1/agent-runs/${encodeURIComponent(runId)}`, { headers })
  if (!response.ok) throw new Error(`Could not read recovered run: HTTP ${response.status}`)
  const envelope = await response.json()
  const run = envelope?.data
  if (envelope?.code !== 'OK' || run?.status !== 'FAILED' || run?.errorCode !== 'SERVER_RESTARTED') {
    throw new Error('Interrupted run did not recover to FAILED/SERVER_RESTARTED')
  }
  const eventsResponse = await fetch(
    `${apiBaseUrl}/api/v1/agent-runs/${encodeURIComponent(runId)}/events?afterSequence=0`,
    { headers: { ...headers, Accept: 'text/event-stream' }, signal: AbortSignal.timeout(5_000) },
  )
  if (!eventsResponse.ok) throw new Error(`Could not replay recovered run events: HTTP ${eventsResponse.status}`)
  const eventStream = await eventsResponse.text()
  if (!eventStream.includes('run.failed') || !eventStream.includes('SERVER_RESTARTED')) {
    throw new Error('Recovered run failure event was not replayable')
  }
  process.stdout.write(`${JSON.stringify({ runId, status: run.status, errorCode: run.errorCode, failureEventReplayed: true })}\n`)
} else {
  throw new Error('Usage: node scripts/Recovery-Rehearsal.mjs seed|verify [runId]')
}
