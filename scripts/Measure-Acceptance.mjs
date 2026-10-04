import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { performance } from 'node:perf_hooks'

const required = (name) => {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`Missing required environment variable ${name}`)
  return value
}

const apiBaseUrl = (process.env.LIVE_API_BASE_URL ?? 'http://127.0.0.1:28080').replace(/\/$/, '')
const orderNo = required('LIVE_SYNTHETIC_ORDER_NO')
const username = process.env.LIVE_TEST_USERNAME?.trim() || 'csr'
const password = required('LIVE_TEST_PASSWORD')
const merchantCode = process.env.LIVE_TEST_MERCHANT_CODE?.trim() || 'DEMO_MERCHANT'
const warmupCount = Number(process.env.PERF_WARMUP_COUNT ?? 100)
const sampleCount = Number(process.env.PERF_SAMPLE_COUNT ?? 200)
const concurrency = Number(process.env.PERF_CONCURRENCY ?? 10)
const outputPath = process.env.PERF_OUTPUT_PATH

for (const [name, value] of Object.entries({ warmupCount, sampleCount, concurrency })) {
  if (!Number.isInteger(value) || value < 1) throw new Error(`${name} must be a positive integer`)
}

const loginResponse = await fetch(`${apiBaseUrl}/api/v1/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ merchantCode, username, password }),
})
if (!loginResponse.ok) throw new Error(`Login failed with HTTP ${loginResponse.status}`)
const loginEnvelope = await loginResponse.json()
const accessToken = loginEnvelope?.data?.accessToken
if (loginEnvelope?.code !== 'OK' || typeof accessToken !== 'string') throw new Error('Login returned an invalid response')

const url = `${apiBaseUrl}/api/v1/orders?orderNo=${encodeURIComponent(orderNo)}`
const request = async () => {
  const startedAt = performance.now()
  try {
    const response = await fetch(url, { headers: { Authorization: `Bearer ${accessToken}` } })
    const elapsedMs = performance.now() - startedAt
    await response.arrayBuffer()
    return { elapsedMs, status: response.status }
  } catch {
    return { elapsedMs: performance.now() - startedAt, status: 0 }
  }
}

for (let index = 0; index < warmupCount; index += 1) {
  const result = await request()
  if (result.status !== 200) throw new Error(`Warm-up request ${index + 1} failed with HTTP ${result.status}`)
}

const startedAt = new Date().toISOString()
const measured = []
let nextIndex = 0
await Promise.all(Array.from({ length: Math.min(concurrency, sampleCount) }, async () => {
  while (true) {
    const index = nextIndex
    nextIndex += 1
    if (index >= sampleCount) return
    measured[index] = await request()
  }
}))

const latencies = measured.map(({ elapsedMs }) => elapsedMs).sort((left, right) => left - right)
const percentile = (fraction) => latencies[Math.max(0, Math.ceil(fraction * latencies.length) - 1)]
const errorCount = measured.filter(({ status }) => status !== 200).length
const totalMs = measured.reduce((total, { elapsedMs }) => total + elapsedMs, 0)
const result = {
  startedAt,
  finishedAt: new Date().toISOString(),
  endpoint: '/api/v1/orders?orderNo=<synthetic-order>',
  workload: { warmupCount, sampleCount, concurrency },
  response: {
    p50Ms: Number(percentile(0.5).toFixed(2)),
    p95Ms: Number(percentile(0.95).toFixed(2)),
    maxMs: Number(latencies.at(-1).toFixed(2)),
    meanMs: Number((totalMs / sampleCount).toFixed(2)),
    errorCount,
    statusCounts: Object.fromEntries([...new Set(measured.map(({ status }) => status))]
      .sort((left, right) => left - right)
      .map((status) => [String(status), measured.filter((item) => item.status === status).length])),
  },
}

if (outputPath) {
  const resolvedOutputPath = resolve(outputPath)
  await mkdir(dirname(resolvedOutputPath), { recursive: true })
  await writeFile(resolvedOutputPath, `${JSON.stringify(result, null, 2)}\n`, 'utf8')
}
process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
if (errorCount > 0) process.exitCode = 1
