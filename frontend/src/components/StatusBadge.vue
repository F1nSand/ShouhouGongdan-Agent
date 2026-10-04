<script setup lang="ts">
import { computed } from 'vue'
import { statusLabel } from '../domain/display'

const props = defineProps<{ status?: string | null }>()
const tone = computed(() => {
  const value = props.status ?? ''
  if (['APPROVED', 'EXECUTED', 'RESOLVED', 'COMPLETED', 'PUBLISHED', 'CONFIRMED'].includes(value)) return 'positive'
  if (['REJECTED', 'FAILED', 'EXPIRED', 'CANCELLED', 'INVALIDATED', 'SERVER_RESTARTED'].includes(value)) return 'negative'
  if (['WAITING_APPROVAL', 'PENDING', 'READY', 'READY_TO_PUBLISH', 'WAITING_CONFIRMATION'].includes(value)) return 'attention'
  return 'neutral'
})
</script>

<template>
  <span class="status-badge" :class="`status-badge--${tone}`">
    <span class="status-badge__dot" aria-hidden="true" />{{ statusLabel(status) }}
  </span>
</template>
