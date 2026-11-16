"""Fixed-target TCP relay: the only way out of the isolated worker network.

Listens on RELAY_LISTEN_PORT (default 8180) and forwards every connection to RELAY_TARGET_HOST:RELAY_TARGET_PORT
(default host.docker.internal:8180, i.e. Spring Boot on Windows). It never parses, logs or stores payloads and
cannot be pointed at any other destination by a client. Run: python -m khmerdub_ai.net.relay
Standard library only, so it does not depend on the worker settings or secrets.
"""

from __future__ import annotations

import asyncio
import logging
import os

log = logging.getLogger("khmerdub.relay")


def _target() -> tuple[str, int]:
    return os.environ.get("RELAY_TARGET_HOST", "host.docker.internal"), int(os.environ.get("RELAY_TARGET_PORT", "8180"))


async def _pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    except (ConnectionError, OSError):
        pass
    finally:
        writer.close()


async def _handle(client_reader: asyncio.StreamReader, client_writer: asyncio.StreamWriter) -> None:
    host, port = _target()
    try:
        up_reader, up_writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout=10)
    except (OSError, asyncio.TimeoutError) as e:
        log.warning("relay: target %s:%s unreachable (%s)", host, port, type(e).__name__)
        client_writer.close()
        return
    await asyncio.gather(_pipe(client_reader, up_writer), _pipe(up_reader, client_writer))


async def serve() -> None:
    listen = int(os.environ.get("RELAY_LISTEN_PORT", "8180"))
    server = await asyncio.start_server(_handle, "0.0.0.0", listen)
    log.info("relay listening on :%s -> %s:%s", listen, *_target())
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    asyncio.run(serve())
