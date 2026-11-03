import { http } from './http'

export interface KnowledgeVersion {
  documentId: string
  versionId: string
  title: string
  categoryCode: string
  versionNo: number
  status: string
  effectiveFrom: string
  effectiveTo?: string
  failureReason?: string
  reusedExistingVersion: boolean
}

export interface PolicyChunk {
  chunkId: string
  documentTitle: string
  versionNo: number
  pageNo?: number
  content: string
}

export const knowledgeApi = {
  upload: (form: FormData) => http.upload<KnowledgeVersion>('/knowledge/documents', form),
  version: (id: string) => http.get<KnowledgeVersion>(`/knowledge/versions/${id}`),
  retry: (id: string) => http.post<KnowledgeVersion>(`/knowledge/versions/${id}/retry`),
  publish: (id: string) => http.post<void>(`/knowledge/versions/${id}/publish`),
  search: (query: string, orderId?: string, categoryCode?: string) =>
    http.post<{ chunks: PolicyChunk[] }>('/knowledge/search', { query, orderId, categoryCode }),
}
