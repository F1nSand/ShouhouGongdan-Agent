import { createHash, randomUUID } from 'node:crypto'
import { existsSync } from 'node:fs'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const dataDirectory = resolve(root, 'backend/src/test/resources/evaluation')
const dataFiles = [
  'business-orders-logistics.jsonl',
  'business-policy.jsonl',
  'business-tickets.jsonl',
  'business-inventory.jsonl',
  'business-approvals.jsonl',
  'adversarial.jsonl',
]
const toolActions = {
  query_order: 'READ_ORDER',
  query_logistics: 'READ_LOGISTICS',
  retrieve_policy: 'READ_POLICY',
  query_inventory: 'READ_INVENTORY',
  recommend_transfer: 'PROVIDE_TRANSFER_RECOMMENDATION',
  draft_ticket: 'CREATE_TICKET_DRAFT',
  draft_sensitive_action: 'SUBMIT_SENSITIVE_DRAFT',
}
const defaultLiveSample = [
  'ORD-001', 'ORD-002', 'ORD-011', 'ORD-021',
  'POL-001', 'POL-011', 'POL-021', 'POL-030',
  'TKT-001', 'TKT-002', 'TKT-011', 'TKT-012',
  'INV-001', 'INV-005', 'INV-011', 'INV-015',
  'APR-001', 'APR-002', 'APR-003', 'APR-004',
]

function parseArgs(values) {
  const result = new Map()
  for (let index = 0; index < values.length; index += 1) {
    const argument = values[index]
    if (!argument.startsWith('--')) throw new Error(`Unknown argument ${argument}`)
    result.set(argument.slice(2), values[index + 1]?.startsWith('--') || values[index + 1] === undefined
      ? 'true' : values[++index])
  }
  return result
}

function csvCell(value) {
  const text = Array.isArray(value) ? value.join('|') : value == null ? '' : String(value)
  return `"${text.replaceAll('"', '""')}"`
}

function summarizeError(errorCode) {
  if (!errorCode) return 'NONE'
  if (errorCode === 'ASSERTION_FAILED') return 'ASSERTION'
  if (/FORBIDDEN|UNAUTHENTICATED|SELF_APPROVAL/i.test(errorCode)) return 'AUTHORIZATION'
  if (/INVALID|NOT_FOUND|CONFLICT|UNAVAILABLE|TIMEOUT|FAILED|SERVER_RESTARTED/i.test(errorCode)) return 'DEPENDENCY_OR_BUSINESS'
  return 'APPLICATION'
}

function expectedCitationPattern(fixtureKey) {
  const normalized = String(fixtureKey).toLowerCase()
  if (normalized.includes('refund-and-return')) return /退款|退货|refund|return/i
  if (normalized.includes('logistics')) return /物流|配送|logistics|delivery/i
  if (normalized.includes('exchange-and-repair')) return /换货|维修|exchange|repair/i
  return undefined
}

function resolvePrecondition(caseRecord) {
  if (caseRecord.category === 'approval_workflow') {
    return `缺少与 ${caseRecord.fixtureKey} 对应的可重建待审批动作 fixture；审批是人工角色操作，不由聊天 Agent 执行。`
  }
  if (String(caseRecord.fixtureKey).startsWith('docs/')) {
    const fixturePath = resolve(root, caseRecord.fixtureKey)
    if (!fixturePath.startsWith(root) || !existsSync(fixturePath)) return '政策文件 fixture 不存在。'
  }
  return undefined
}

async function loadCases() {
  const records = []
  for (const filename of dataFiles) {
    const lines = (await readFile(resolve(dataDirectory, filename), 'utf8')).split(/\r?\n/).filter(Boolean)
    for (const line of lines) {
      const parsed = JSON.parse(line)
      parsed.suite = filename === 'adversarial.jsonl' ? 'adversarial' : 'business'
      records.push(parsed)
    }
  }
  const ids = new Set()
  for (const record of records) {
    if (!record.caseId || ids.has(record.caseId)) throw new Error(`Missing or duplicate caseId ${record.caseId ?? ''}`)
    ids.add(record.caseId)
    if (!record.input || !record.role || !record.fixtureKey || !Array.isArray(record.expectedTools)
        || !Array.isArray(record.allowedActions)) throw new Error(`Invalid evaluation record ${record.caseId}`)
  }
  return records
}

function credentialsFor(role) {
  const credentials = {
    CUSTOMER_SERVICE: ['csr', process.env.LIVE_TEST_PASSWORD],
    WAREHOUSE_OPERATOR: ['warehouse', process.env.LIVE_TEST_WAREHOUSE_PASSWORD],
    SERVICE_SUPERVISOR: ['supervisor', process.env.LIVE_TEST_SUPERVISOR_PASSWORD],
  }
  const entry = credentials[role]
  if (!entry) throw new Error(`Unsupported evaluation role ${role}`)
  if (!entry[1]) throw new Error(`Missing password for role ${role}`)
  return { username: entry[0], password: entry[1] }
}

async function requestJson(baseUrl, path, token, init = {}) {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const response = await fetch(`${baseUrl}${path}`, { ...init, headers })
  const body = await response.json().catch(() => null)
  return { response, body }
}

async function createSession(baseUrl, role, merchantCode, cache) {
  if (cache.has(role)) return cache.get(role)
  const identity = credentialsFor(role)
  const { response, body } = await requestJson(baseUrl, '/api/v1/auth/login', undefined, {
    method: 'POST',
    body: JSON.stringify({ merchantCode, username: identity.username, password: identity.password }),
  })
  if (!response.ok || body?.code !== 'OK' || !body?.data?.accessToken) {
    throw new Error(`Login failed for role ${role} (HTTP ${response.status})`)
  }
  const session = { token: body.data.accessToken, identity }
  const conversation = await requestJson(baseUrl, '/api/v1/conversations', session.token, {
    method: 'POST',
    body: JSON.stringify({ title: `评测会话 ${role}` }),
  })
  if (!conversation.response.ok || conversation.body?.code !== 'OK') {
    throw new Error(`Conversation setup failed for role ${role} (HTTP ${conversation.response.status})`)
  }
  session.conversationId = conversation.body.data.conversationId
  cache.set(role, session)
  return session
}

async function collectRunEvents(baseUrl, runId, token) {
  const response = await fetch(`${baseUrl}/api/v1/agent-runs/${encodeURIComponent(runId)}/events?afterSequence=0`, {
    headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
    signal: AbortSignal.timeout(65_000),
  })
  if (!response.ok || !response.body) throw new Error(`SSE failed with HTTP ${response.status}`)
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  const events = []
  let buffer = ''
  let terminal = false
  const consume = (frame) => {
    let eventName
    let dataText = ''
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('event:')) eventName = line.slice(6).trim()
      if (line.startsWith('data:')) dataText += line.slice(5).trimStart()
    }
    if (!dataText) return
    try {
      const event = JSON.parse(dataText)
      const eventType = event.eventType || eventName
      const record = { type: eventType, payload: event.payload ?? {} }
      events.push(record)
      if (['answer.final', 'run.failed', 'run.cancelled'].includes(eventType)) terminal = true
    } catch {
      // Malformed event payloads remain visible through the missing terminal event assertion.
    }
  }
  while (!terminal) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const frames = buffer.split(/\r?\n\r?\n/)
    buffer = frames.pop() ?? ''
    for (const frame of frames) consume(frame)
  }
  if (!terminal && buffer.trim()) consume(buffer)
  await reader.cancel().catch(() => undefined)
  return events
}

async function runCase(baseUrl, caseRecord, profile, sessions, estimatedBudget) {
  const missing = resolvePrecondition(caseRecord)
  if (missing) {
    return {
      caseId: caseRecord.caseId,
      suite: caseRecord.suite,
      category: caseRecord.category,
      role: caseRecord.role,
      profile,
      status: 'NOT_RUN_FIXTURE_MISSING',
      errorClass: 'FIXTURE_MISSING',
      reason: missing,
      estimatedModelCalls: 0,
    }
  }
  const session = await createSession(baseUrl, caseRecord.role,
    process.env.LIVE_TEST_MERCHANT_CODE?.trim() || 'DEMO_MERCHANT', sessions)
  const requestId = randomUUID()
  const estimatedCalls = profile === 'live' ? 1 + (caseRecord.citationRequired ? 1 : 0) : 0
  estimatedBudget.used += estimatedCalls
  const start = performance.now()
  try {
    const started = await requestJson(baseUrl,
      `/api/v1/conversations/${encodeURIComponent(session.conversationId)}/runs`, session.token, {
        method: 'POST',
        body: JSON.stringify({ requestId, message: caseRecord.input }),
      })
    if (!started.response.ok || started.body?.code !== 'OK' || !started.body?.data?.runId) {
      const errorCode = started.body?.code ?? `HTTP_${started.response.status}`
      return {
        caseId: caseRecord.caseId, suite: caseRecord.suite, category: caseRecord.category,
        role: caseRecord.role, profile, status: 'FAIL', errorClass: summarizeError(errorCode),
        errorCode, reason: 'Agent run request was rejected.', estimatedModelCalls: estimatedCalls,
        elapsedMs: Number((performance.now() - start).toFixed(2)),
      }
    }
    const runId = started.body.data.runId
    const events = await collectRunEvents(baseUrl, runId, session.token)
    const observedTools = [...new Set(events.filter(({ type }) => type === 'tool.started')
      .map(({ payload }) => payload.toolName).filter(Boolean))].sort()
    const toolFailures = events.filter(({ type, payload }) => type === 'tool.completed' && payload.success === false)
      .map(({ payload }) => `${payload.toolName ?? 'unknown'}:${payload.errorCode ?? 'TOOL_FAILED'}`)
    const citationTitles = events.filter(({ type }) => type === 'citation')
      .map(({ payload }) => payload.documentTitle).filter(Boolean)
    const draftTypes = events.filter(({ type }) => type === 'draft.created')
      .map(({ payload }) => payload.type).filter(Boolean)
    const failureEvent = events.find(({ type }) => type === 'run.failed')
    const finalEvent = events.find(({ type }) => type === 'answer.final')
    const expectedTools = [...new Set(caseRecord.expectedTools)].sort()
    const expectedActions = new Set(caseRecord.allowedActions)
    const observedActions = observedTools.map((tool) => toolActions[tool] ?? `UNKNOWN:${tool}`)
    const actionViolations = observedActions.filter((action) => !expectedActions.has(action))
    const toolSetPass = expectedTools.length === observedTools.length
      && expectedTools.every((tool, index) => tool === observedTools[index])
    const citationPattern = expectedCitationPattern(caseRecord.fixtureKey)
    const citationPass = !caseRecord.citationRequired
      || (citationTitles.length > 0 && (!citationPattern || citationTitles.some((title) => citationPattern.test(title))))
    const answerText = typeof finalEvent?.payload?.answer === 'string' ? finalEvent.payload.answer : ''
    const expectedAnswerContains = caseRecord.expectedAnswerContains ?? []
    const answerPass = expectedAnswerContains.every((phrase) => answerText.includes(phrase))
    const draftPass = draftTypes.every((type) => type === 'CREATE_TICKET' || type === 'REQUEST_REFUND'
      || type === 'REQUEST_STOCK_TRANSFER')
    const status = !finalEvent || failureEvent || toolFailures.length || !toolSetPass
      || actionViolations.length || !citationPass || !answerPass || !draftPass ? 'FAIL' : 'PASS'
    const errorCode = failureEvent?.payload?.errorCode
      ?? (toolFailures.length ? 'TOOL_FAILURE' : undefined)
    return {
      caseId: caseRecord.caseId,
      suite: caseRecord.suite,
      category: caseRecord.category,
      role: caseRecord.role,
      profile,
      status,
      errorClass: summarizeError(errorCode ?? (status === 'FAIL' ? 'ASSERTION_FAILED' : undefined)),
      runId,
      expectedTools,
      observedTools,
      toolSetPass,
      toolFailures,
      allowedActions: caseRecord.allowedActions,
      observedActions,
      actionViolations,
      citationRequired: Boolean(caseRecord.citationRequired),
      citationTitles,
      citationPass,
      expectedAnswerContains,
      answerPass,
      answerText,
      draftTypes,
      answerFinal: Boolean(finalEvent),
      elapsedMs: Number((performance.now() - start).toFixed(2)),
      estimatedModelCalls: estimatedCalls,
    }
  } catch (error) {
    return {
      caseId: caseRecord.caseId, suite: caseRecord.suite, category: caseRecord.category,
      role: caseRecord.role, profile, status: 'FAIL', errorClass: 'DEPENDENCY',
      errorCode: 'EVALUATION_REQUEST_FAILED', reason: error instanceof Error ? error.message : 'Request failed.',
      estimatedModelCalls: estimatedCalls,
      elapsedMs: Number((performance.now() - start).toFixed(2)),
    }
  }
}

const args = parseArgs(process.argv.slice(2))
const profile = args.get('profile') ?? 'mock'
if (!['mock', 'live'].includes(profile)) throw new Error('--profile must be mock or live')
const modelBudget = Number(args.get('model-budget') ?? 0)
if (profile === 'live' && (!Number.isInteger(modelBudget) || modelBudget < 1 || modelBudget > 50)) {
  throw new Error('Live profile requires --model-budget between 1 and 50')
}

let cases = await loadCases()
const requestedIds = args.get('caseIds')?.split(',').map((value) => value.trim()).filter(Boolean)
if (profile === 'live' && !requestedIds) {
  cases = cases.filter((record) => defaultLiveSample.includes(record.caseId))
} else if (requestedIds) {
  const selected = new Set(requestedIds)
  cases = cases.filter((record) => selected.has(record.caseId))
  const found = new Set(cases.map(({ caseId }) => caseId))
  const unknown = requestedIds.filter((caseId) => !found.has(caseId))
  if (unknown.length) throw new Error(`Unknown caseIds: ${unknown.join(', ')}`)
}
const suite = args.get('suite')
if (suite && !['all', 'business', 'adversarial'].includes(suite)) {
  throw new Error('--suite must be all, business, or adversarial')
}
if (suite && suite !== 'all') cases = cases.filter((record) => record.suite === suite)
const limit = args.has('limit') ? Number(args.get('limit')) : Number.POSITIVE_INFINITY
if (!Number.isInteger(limit) && limit !== Number.POSITIVE_INFINITY) throw new Error('--limit must be a positive integer')
if (limit < 1) throw new Error('--limit must be a positive integer')
cases = cases.slice(0, limit)

if (profile === 'live') {
  const plannedMaximum = cases.filter((record) => !resolvePrecondition(record))
    .reduce((total, record) => total + 1 + (record.citationRequired ? 1 : 0), 0)
  if (plannedMaximum > modelBudget) {
    throw new Error(`Selected cases estimate ${plannedMaximum} model requests, exceeding --model-budget ${modelBudget}`)
  }
}

const baseUrl = (process.env.LIVE_API_BASE_URL ?? 'http://127.0.0.1:28080').replace(/\/$/, '')
const sessions = new Map()
const estimatedBudget = { used: 0 }
const results = []
for (const caseRecord of cases) {
  results.push(await runCase(baseUrl, caseRecord, profile, sessions, estimatedBudget))
  if (results.length % 10 === 0 || results.length === cases.length) {
    process.stdout.write(`Processed ${results.length}/${cases.length} cases.\n`)
  }
}

const executed = results.filter((result) => !result.status.startsWith('NOT_RUN'))
const categorySummary = Object.fromEntries([...new Set(results.map(({ category }) => category))].sort()
  .map((category) => {
    const rows = results.filter((result) => result.category === category)
    const done = rows.filter((result) => !result.status.startsWith('NOT_RUN'))
    return [category, {
      total: rows.length,
      executed: done.length,
      passed: done.filter((result) => result.status === 'PASS').length,
      failed: done.filter((result) => result.status === 'FAIL').length,
      notRun: rows.length - done.length,
    }]
  }))
const allInputs = await Promise.all(dataFiles.map((filename) => readFile(resolve(dataDirectory, filename), 'utf8')))
const allRecords = allInputs.flatMap((text) => text.split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line)))
const inputCounts = new Map()
for (const record of allRecords) {
  const digest = createHash('sha256').update(record.input.trim()).digest('hex')
  inputCounts.set(digest, (inputCounts.get(digest) ?? 0) + 1)
}
const summary = {
  measuredAt: new Date().toISOString(),
  profile,
  dataset: {
    totalCases: allRecords.length,
    selectedCases: cases.length,
    businessCases: allRecords.filter((record) => !record.caseId.startsWith('ADV-')).length,
    adversarialCases: allRecords.filter((record) => record.caseId.startsWith('ADV-')).length,
    duplicateInputCases: [...inputCounts.values()].reduce((total, count) => total + Math.max(0, count - 1), 0),
  },
  execution: {
    executed: executed.length,
    passed: executed.filter((result) => result.status === 'PASS').length,
    failed: executed.filter((result) => result.status === 'FAIL').length,
    notRun: results.length - executed.length,
    mockOnlyEvidence: profile === 'mock',
    estimatedModelCalls: estimatedBudget.used,
    declaredModelBudget: profile === 'live' ? modelBudget : 0,
  },
  categorySummary,
  limitations: [
    'Evaluation checks observed tool calls, action classes, citations and run completion; it does not automatically judge free-text factual quality.',
    'Approval prompts are reported as fixture-missing until named pending actions are created and mapped to deterministic fixtures.',
    'Mock results are deterministic application-path evidence, not real-model quality scores.',
  ],
}

const outputDirectory = resolve(root, args.get('output-dir') ?? `docs/reports/evaluation-${new Date().toISOString().slice(0, 10)}-${profile}`)
await mkdir(outputDirectory, { recursive: true })
const jsonl = results.map((result) => JSON.stringify(result)).join('\n') + (results.length ? '\n' : '')
const summaryPath = resolve(outputDirectory, 'summary.json')
const resultsPath = resolve(outputDirectory, 'results.jsonl')
const csvPath = resolve(outputDirectory, 'results.csv')
await writeFile(resultsPath, jsonl, 'utf8')
await writeFile(summaryPath, `${JSON.stringify(summary, null, 2)}\n`, 'utf8')
const columns = ['caseId', 'suite', 'category', 'role', 'profile', 'status', 'errorClass', 'runId',
  'expectedTools', 'observedTools', 'toolSetPass', 'actionViolations', 'citationPass', 'elapsedMs', 'reason']
const csv = [columns.join(','), ...results.map((result) => columns.map((key) => csvCell(result[key])).join(','))].join('\n') + '\n'
await writeFile(csvPath, csv, 'utf8')
process.stdout.write(`${JSON.stringify({ summary, outputDirectory }, null, 2)}\n`)
