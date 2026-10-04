export interface ApiEnvelope<T> {
  code: string
  data: T
  traceId?: string
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string,
    readonly traceId?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

function accessToken(): string | undefined {
  try {
    const session = sessionStorage.getItem('aftercare.session')
    return session ? (JSON.parse(session) as { accessToken?: string }).accessToken : undefined
  } catch {
    return undefined
  }
}

async function send<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  const isLogin = path === '/auth/login'
  const token = isLogin ? undefined : accessToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(`/api/v1${path}`, { ...init, headers })
  if (response.status === 401 && !isLogin) {
    window.dispatchEvent(new CustomEvent('auth:expired'))
  }

  const envelope = (await response.json().catch(() => null)) as ApiEnvelope<T> | null
  if (!response.ok || !envelope || envelope.code !== 'OK') {
    const body = envelope as unknown as { code?: string; message?: string; data?: { message?: string }; traceId?: string } | null
    throw new ApiError(body?.message ?? body?.data?.message ?? `请求失败（HTTP ${response.status}）`, response.status,
      body?.code ?? 'HTTP_ERROR', body?.traceId)
  }
  return envelope.data
}

export const http = {
  get: <T>(path: string, signal?: AbortSignal) => send<T>(path, { method: 'GET', signal }),
  post: <T>(path: string, body?: unknown, headers?: HeadersInit, signal?: AbortSignal) =>
    send<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body), headers, signal }),
  upload: <T>(path: string, body: FormData, signal?: AbortSignal) =>
    send<T>(path, { method: 'POST', body, signal }),
}

export function idempotencyHeaders(key: string = crypto.randomUUID()): HeadersInit {
  return { 'Idempotency-Key': key }
}
