import { Client, type StompSubscription } from '@stomp/stompjs'
import type { SystemHealth } from '~/stores/system'

type Handler = (body: unknown) => void

/**
 * One STOMP/WebSocket connection for the whole app. Pages subscribe to topics with $live.subscribe(); every
 * subscription is restored automatically after the API restarts and the connection comes back.
 */
export default defineNuxtPlugin(() => {
  const config = useRuntimeConfig()
  const system = useSystemStore()
  const handlers = new Map<string, Set<Handler>>()
  const active = new Map<string, StompSubscription>()

  const client = new Client({
    brokerURL: config.public.wsUrl,
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    debug: () => {},
  })

  function attach(topic: string) {
    if (!client.connected || active.has(topic)) return
    active.set(topic, client.subscribe(topic, (message) => {
      const body = JSON.parse(message.body)
      handlers.get(topic)?.forEach((h) => h(body))
    }))
  }

  client.onConnect = () => {
    system.liveConnected = true
    active.clear()
    for (const topic of handlers.keys()) attach(topic)
  }
  client.onWebSocketClose = () => {
    system.liveConnected = false
    active.clear()
  }

  const live = {
    /** Returns an unsubscribe function. */
    subscribe(topic: string, handler: Handler): () => void {
      if (!handlers.has(topic)) handlers.set(topic, new Set())
      handlers.get(topic)!.add(handler)
      attach(topic)
      return () => {
        const set = handlers.get(topic)
        set?.delete(handler)
        if (set && set.size === 0) {
          handlers.delete(topic)
          active.get(topic)?.unsubscribe()
          active.delete(topic)
        }
      }
    },
  }

  live.subscribe('/topic/system/health', (body) => system.applyHealth(body as SystemHealth, 'websocket'))
  client.activate()

  return { provide: { live } }
})
