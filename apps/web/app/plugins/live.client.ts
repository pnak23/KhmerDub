import { Client } from '@stomp/stompjs'

/**
 * One STOMP/WebSocket connection for the whole app. Spring Boot pushes health (and, from M2, job
 * progress) to /topic/... topics. The connection reconnects by itself if the API restarts.
 */
export default defineNuxtPlugin(() => {
  const config = useRuntimeConfig()
  const system = useSystemStore()

  const client = new Client({
    brokerURL: config.public.wsUrl,
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    debug: () => {},
  })

  client.onConnect = () => {
    system.liveConnected = true
    client.subscribe('/topic/system/health', (message) => {
      system.applyHealth(JSON.parse(message.body), 'websocket')
    })
  }
  client.onWebSocketClose = () => {
    system.liveConnected = false
  }

  client.activate()
})
