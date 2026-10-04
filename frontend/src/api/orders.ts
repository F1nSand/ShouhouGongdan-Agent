import { http } from './http'

export interface Order {
  orderId: string
  orderNo: string
  status: string
  purchasedAt: string
  shippedAt?: string
  paidAmountCents: number
  refundedAmountCents: number
  promisedDeliveryAt?: string
  customerName: string
  maskedPhone: string
  items: Array<{ skuId: string; skuCode: string; name: string; quantity: number; unitPriceCents: number }>
}

export interface Logistics {
  orderId: string
  calculatedAt: string
  lastUpdateAt?: string
  dataInsufficient: boolean
  exceptionCodes: string[]
  events: Array<{ eventCode: string; eventTime: string; carrierCode?: string; trackingNo?: string; description: string }>
}

export const ordersApi = {
  getByNumber: (orderNo: string) => http.get<Order>(`/orders?orderNo=${encodeURIComponent(orderNo)}`),
  logistics: (orderId: string) => http.get<Logistics>(`/orders/${orderId}/logistics`),
}
