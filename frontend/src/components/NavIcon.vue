<script setup lang="ts">
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 20 })

const paths: Record<string, string[]> = {
  workspace: ['M3 4.5A1.5 1.5 0 0 1 4.5 3h6A1.5 1.5 0 0 1 12 4.5v4A1.5 1.5 0 0 1 10.5 10h-6A1.5 1.5 0 0 1 3 8.5z', 'M15 3.75h5.25v5.5H15z', 'M3.75 13h5.5v7h-5.5z', 'M12 14.5a1.5 1.5 0 0 1 1.5-1.5h5A1.5 1.5 0 0 1 20 14.5v4a1.5 1.5 0 0 1-1.5 1.5h-5a1.5 1.5 0 0 1-1.5-1.5z'],
  tickets: ['M4 4.5h16v15H4z', 'M8 8h8', 'M8 12h8', 'M8 16h5'],
  inventory: ['M3 7.5 12 3l9 4.5-9 4.5z', 'M3 12l9 4.5 9-4.5', 'M3 16.5 12 21l9-4.5', 'M12 12v9'],
  approvals: ['M12 3 19 6v5c0 5-3.2 8.3-7 10-3.8-1.7-7-5-7-10V6z', 'm9 12 2 2 4-4'],
  knowledge: ['M4 4.5A2.5 2.5 0 0 1 6.5 2H20v17H6.5A2.5 2.5 0 0 0 4 21z', 'M4 4.5v16.5', 'M8 7h8', 'M8 11h8'],
  audit: ['M4 5h16', 'M4 10h16', 'M4 15h10', 'M4 20h8', 'M17 15v5', 'M14.5 17.5h5'],
  add: ['M12 5v14', 'M5 12h14'],
  logout: ['M10 4H5v16h5', 'M14 8l4 4-4 4', 'M8 12h10'],
  arrow: ['M4 12h15', 'm13 6 6 6-6 6'],
  chevron: ['m9 18 6-6-6-6'],
  message: ['M4 5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5v8a2.5 2.5 0 0 1-2.5 2.5H10l-5 4v-4.5a2.5 2.5 0 0 1-1-2z'],
  close: ['M6 6l12 12', 'M18 6 6 18'],
  check: ['m5 12 4 4L19 6'],
  search: ['M10.8 18a7.2 7.2 0 1 0 0-14.4 7.2 7.2 0 0 0 0 14.4z', 'm16 16 4.5 4.5'],
  upload: ['M12 16V4', 'm7 9 5-5 5 5', 'M4 16v4h16v-4'],
  box: ['M3 7.5 12 3l9 4.5v9L12 21l-9-4.5z', 'M3 7.5l9 4.5 9-4.5', 'M12 12v9'],
}
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path v-for="(path, index) in (paths[name] ?? paths.workspace)" :key="index" :d="path" />
  </svg>
</template>
