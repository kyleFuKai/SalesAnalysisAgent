import { apiRequest, fetchApi } from './http'

export interface ChatResponse {
  sessionId: string
  reply: string
  durationMs: number
}

export function askSalesAgent(sessionId: string, message: string) {
  return apiRequest<ChatResponse>('/agent/chat', {
    method: 'POST',
    body: JSON.stringify({ sessionId, message }),
  })
}

export function clearSalesSession(sessionId: string) {
  return fetchApi(`/agent/session/${encodeURIComponent(sessionId)}`, { method: 'DELETE' }).then(() => undefined)
}
