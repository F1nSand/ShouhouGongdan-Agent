import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { performance } from 'node:perf_hooks'

const required = (name) => {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`Missing required environment variable ${name}`)
  return value
}

const apiBaseUrl = (process.env.LIVE_API_BASE_URL ?? 'http://127.0.0.1:28080').replace(/\/$/, '')
const merchantCode = process.env.LIVE_TEST_MERCHANT_CODE?.trim() || 'DEMO_MERCHANT'
const username = process.env.LIVE_TEST_USERNAME?.trim() || 'csr'
const password = required('LIVE_TEST_PASSWORD')
const orderNo = required('LIVE_SYNTHETIC_ORDER_NO')
const outputPath = process.env.AGENT_TIMING_OUTPUT_PATH

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

const conversationResponse = await fetch(`${apiBaseUrl}/api/v1/conversations`, {
  method: 'POST',
  headers: { ...headers, 'Content-Type': 'application/json' },
  body: JSON.stringify({ title: '合成模型响应时间测量' }),
})
if (!conversationResponse.ok) throw new Error(`Conversation creation failed with HTTP ${conversationResponse.status}`)
const conversationEnvelope = await conversationResponse.json()
const conversationId = conversationEnvelope?.data?.conversationId
if (conversationEnvelope?.code !== 'OK' || !conversationId) throw new Error('Conversation creation returned an invalid response')

const message = `请查询订单 ${orderNo} 的当前状态和物流轨迹，并说明最近一条物流记录。`
const requestId = randomUUID()
const startedAt = performance.now()
const runResponse = await fetch(`${apiBaseUrl}/api/v1/conversations/${encodeURIComponent(conversationId)}/runs`, {
  method: 'POST',
  headers: { ...headers, 'Content-Type': 'application/json' },
  body: JSON.stringify({ requestId, message }),
})
if (!runResponse.ok) throw new Error(`Agent run creation failed with HTTP ${runResponse.status}`)
const runEnvelope = await runResponse.json()
const runId = runEnvelope?.data?.runId
if (runEnvelope?.code !== 'OK' || !runId) throw new Error('Agent run creation returned an invalid response')
const acceptedMs = performance.now() - startedAt

const eventResponse = await fetch(`${apiBaseUrl}/api/v1/agent-runs/${encodeURIComponent(runId)}/events?afterSequence=0`, {
  headers: { ...headers, Accept: 'text/event-stream' },
  signal: AbortSignal.timeout(65_000),
})
if (!eventResponse.ok || !eventResponse.body) throw new Error(`SSE connection failed with HTTP ${eventResponse.status}`)

const reader = eventResponse.body.getReader()
const decoder = new TextDecoder()
let buffer = ''
let firstEventMs
let firstProgressMs
let finalAnswerMs
const eventTypes = []
const progressEvents = new Set(['tool.started', 'tool.completed', 'citation', 'draft.created', 'answer.delta'])
const consumeBlock = (block) => {
  let eventType = ''
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) eventType = line.slice(6).trim()
  }
  if (!eventType) return
  eventTypes.push(eventType)
  const elapsedMs = performance.now() - startedAt
  if (firstEventMs === undefined) firstEventMs = elapsedMs
  if (firstProgressMs === undefined && progressEvents.has(eventType)) firstProgressMs = elapsedMs
  if (eventType === 'answer.final') finalAnswerMs = elapsedMs
}

while (finalAnswerMs === undefined) {
  const { value, done } = await reader.read()
  if (done) break
  buffer += decoder.decode(value, { stream: true })
  const blocks = buffer.split(/\r?\n\r?\n/)
  buffer = blocks.pop() ?? ''
  for (const block of blocks) consumeBlock(block)
}
if (finalAnswerMs === undefined && buffer.trim()) consumeBlock(buffer)
if (finalAnswerMs === undefined) throw new Error(`Agent run ended without answer.final (events: ${eventTypes.join(', ')})`)

const result = {
  measuredAt: new Date().toISOString(),
  route: 'Nginx -> backend -> DeepSeek -> read-only order/logistics tools',
  runId,
  requestAcceptedMs: Number(acceptedMs.toFixed(2)),
  firstSseEventMs: Number(firstEventMs.toFixed(2)),
  firstProgressEventMs: firstProgressMs === undefined ? null : Number(firstProgressMs.toFixed(2)),
  finalAnswerMs: Number(finalAnswerMs.toFixed(2)),
  eventTypes,
  externalModelRequests: 1,
}

if (outputPath) {
  const resolvedOutputPath = resolve(outputPath)
  await mkdir(dirname(resolvedOutputPath), { recursive: true })
  await writeFile(resolvedOutputPath, `${JSON.stringify(result, null, 2)}\n`, 'utf8')
}
process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
