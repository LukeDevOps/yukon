---
status: accepted
---

# HTTP and protobuf rather than gRPC

Payloads are protobuf messages posted over HTTP/1.1 keep-alive with the JDK's built-in `java.net.http.HttpClient`, as `application/x-protobuf`. Both options serialise with protobuf, so the wire cost is the same. gRPC's advantage is HTTP/2 multiplexing and streaming, which matters at high RPC rates and not for one batch POST per instance per minute. gRPC's cost for a javaagent is grpc-java plus Netty shaded into every customer JVM, with collision risk against applications that already use gRPC.

## Consequences

- No extra runtime dependency for transport. ByteBuddy, protobuf-java and the Kotlin stdlib are relocated in the shaded jar for the same collision reason.
- Retries cover only failures that describe the server's state: connection and timeout errors, any 5xx, 408 and 429. Every other 4xx fails at once, since resending the same bytes cannot change the answer.
- Revisit gRPC only if continuous streaming ever replaces periodic batching.
