import { http } from './http'

export interface WarehouseStock {
  warehouseId: string
  warehouseCode: string
  warehouseName: string
  onHand: number
  reserved: number
  available: number
  safetyStock: number
  allocatable: number
  inventoryVersion: number
}

export interface InventorySnapshot {
  skuId: string
  skuCode: string
  skuName: string
  calculatedAt: string
  warehouses: WarehouseStock[]
}

export interface TransferCandidate {
  sourceWarehouseId: string
  available: number
  allocatable: number
  inventoryVersion: number
  estimatedDays: number
  costCentsPerUnit: number
}

export interface TransferRecommendation {
  skuId: string
  targetWarehouseId: string
  quantity: number
  calculatedAt: string
  candidates: TransferCandidate[]
  reason?: string
}

export const inventoryApi = {
  get: (skuCode: string) => http.get<InventorySnapshot>(`/inventory?skuCode=${encodeURIComponent(skuCode)}`),
  recommend: (skuId: string, targetWarehouseId: string, quantity: number) =>
    http.post<TransferRecommendation>('/inventory/recommendations', { skuId, targetWarehouseId, quantity }),
}
