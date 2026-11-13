// KhmerDub AI frontend. A local single-page app: the browser talks only to Spring Boot.
// Ports come from the repo-root .env (scripts/dev.ps1 exports them), with the documented defaults.
const apiPort = process.env.API_PORT ?? '8180'
const webPort = Number(process.env.WEB_PORT ?? 3000)

export default defineNuxtConfig({
  compatibilityDate: '2026-11-01',
  // Local desktop-style app: no SEO needs. Client rendering keeps BootstrapVueNext and WebSockets simple.
  ssr: false,
  devtools: { enabled: false },
  telemetry: false,

  modules: ['@pinia/nuxt', '@bootstrap-vue-next/nuxt', '@nuxt/icon'],

  css: ['bootstrap/dist/css/bootstrap.min.css', '~/assets/scss/main.scss'],

  app: {
    head: {
      title: 'KhmerDub AI',
      htmlAttrs: { lang: 'en' },
      meta: [{ name: 'viewport', content: 'width=device-width, initial-scale=1' }],
    },
  },

  devServer: { host: '127.0.0.1', port: webPort },

  runtimeConfig: {
    public: {
      appVersion: '0.1.0',
      // The browser connects to the STOMP endpoint directly; Spring Boot checks the Origin.
      wsUrl: `ws://127.0.0.1:${apiPort}/ws`,
      // Movie uploads go straight to Spring Boot (CORS is enabled for that one endpoint only).
      apiDirectUrl: `http://127.0.0.1:${apiPort}`,
    },
  },

  // REST calls are same-origin (/api/...) and proxied to Spring Boot, so the browser never needs CORS.
  routeRules: {
    '/api/**': { proxy: `http://127.0.0.1:${apiPort}/api/**` },
  },

  // Icons come only from the locally installed Iconify set: no requests to the public Iconify API.
  icon: {
    provider: 'server',
    serverBundle: { collections: ['tabler'] },
    clientBundle: { scan: true },
    fallbackToApi: false,
  },

  typescript: { strict: true },
})
