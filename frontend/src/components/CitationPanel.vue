<script setup lang="ts">
import type { Citation } from '../api/agent'

defineProps<{ citations: Citation[]; compact?: boolean }>()
</script>

<template>
  <section v-if="citations.length" class="citation-list" aria-label="回答依据">
    <article v-for="(citation, index) in citations" :key="citation.citationId ?? `${citation.documentTitle}-${index}`" class="citation-card">
      <div class="citation-card__index">{{ String(index + 1).padStart(2, '0') }}</div>
      <div class="citation-card__body">
        <div class="citation-card__heading">
          <strong>{{ citation.documentTitle }}</strong>
          <span>第 {{ citation.version ?? citation.versionNo ?? '—' }} 版</span>
        </div>
        <p v-if="!compact && (citation.quote || citation.content)">{{ citation.quote ?? citation.content }}</p>
        <small>{{ citation.page ?? citation.pageNo ? `第 ${citation.page ?? citation.pageNo} 页` : '已发布政策' }}</small>
      </div>
    </article>
  </section>
</template>
