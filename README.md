# Qwixx
A Raspberry Pi game made in Angular

# SSE

Before implementing Server Sent Events the bandwidth was 3kB per poll. Now: after the layout was separated from the mutable data, and SSE was implemented; a game can be played with only sending 300kB in total.

## nginx proxy buffering

When the app runs behind nginx, SSE events are buffered by default and only flushed when the buffer fills or the connection closes. This causes passive players to miss individual game events (e.g. a dice roll) until the next event arrives and flushes the buffer.

Both SSE endpoints (`/gamestates/{sessionId}/stream` and `/games/{sessionId}/lobby/stream`) respond with the header:

```
X-Accel-Buffering: no
```

This tells nginx to disable buffering for that connection and pass each event through immediately. Without this header, players would only see state updates in batches rather than in real time.
