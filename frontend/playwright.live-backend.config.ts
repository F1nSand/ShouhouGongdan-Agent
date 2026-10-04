import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/live-backend',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: process.env.LIVE_BACKEND_BASE_URL || 'http://127.0.0.1:28080',
    headless: false,
    trace: 'retain-on-failure',
    ...devices['Desktop Chrome'],
  },
})
