import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

// Unit tests for plain TypeScript logic (stores, utils). They don't need the Nuxt runtime.
export default defineConfig({
  resolve: {
    alias: { '~': fileURLToPath(new URL('./app', import.meta.url)) },
  },
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts'],
  },
})
