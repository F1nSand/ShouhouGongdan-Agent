import { http } from './http'

export type Role = 'CUSTOMER_SERVICE' | 'SERVICE_SUPERVISOR' | 'WAREHOUSE_OPERATOR' | 'ADMIN'

export interface AuthIdentity {
  tenantId: string
  actorId: string
  role: Role
}

export interface LoginResult extends AuthIdentity {
  accessToken: string
  tokenType: string
  expiresAt: string
}

export const authApi = {
  login: (merchantCode: string, username: string, password: string) =>
    http.post<LoginResult>('/auth/login', { merchantCode, username, password }),
  me: () => http.get<AuthIdentity>('/auth/me'),
}
