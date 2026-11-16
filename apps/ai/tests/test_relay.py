"""The fixed-target relay forwards bytes both ways to its configured target only."""

from __future__ import annotations

import asyncio

from khmerdub_ai.net import relay


def test_relay_forwards_to_fixed_target(monkeypatch):
    async def scenario():
        async def echo(reader, writer):
            data = await reader.read(100)
            writer.write(b"echo:" + data)
            await writer.drain()
            writer.close()

        target = await asyncio.start_server(echo, "127.0.0.1", 0)
        port = target.sockets[0].getsockname()[1]
        monkeypatch.setenv("RELAY_TARGET_HOST", "127.0.0.1")
        monkeypatch.setenv("RELAY_TARGET_PORT", str(port))
        server = await asyncio.start_server(relay._handle, "127.0.0.1", 0)  # noqa: SLF001
        relay_port = server.sockets[0].getsockname()[1]
        reader, writer = await asyncio.open_connection("127.0.0.1", relay_port)
        # The client cannot choose a destination: whatever it sends goes to the fixed target.
        writer.write(b"CONNECT huggingface.co:443 HTTP/1.1\r\n\r\n")
        await writer.drain()
        reply = await asyncio.wait_for(reader.read(200), 5)
        writer.close()
        server.close()
        target.close()
        return reply

    assert asyncio.run(scenario()) == b"echo:CONNECT huggingface.co:443 HTTP/1.1\r\n\r\n"
