# TCP transport regression tests

This suite compiles the production `TcpFrameCodec`, `TcpConnection`, and `TcpServer` classes directly. It has no external dependencies and uses real TCP sockets bound to the loopback interface. Java 25 or newer is required; compilation targets Java 25.

Run from any working directory:

```powershell
powershell -ExecutionPolicy Bypass -File ./scripts/test-tcp.ps1
# If the default JDK is older:
./scripts/test-tcp.ps1 -JavaHome 'C:\Program Files\Java\jdk-25'
```

On Linux or macOS:

```sh
sh ./scripts/test-tcp.sh
```

Both runners honor `JAVA_HOME`, compile only the dependency-free transport, client adapter, API envelope, and test sources, and write class files to the ignored `build/transport-tests` directory. They fail with a nonzero exit status if compilation or any test fails. Each test has a bounded timeout, so a blocked read is reported as a failure instead of hanging the suite indefinitely.

The cases cover minimum and maximum frame sizes, network byte order, fragmented and coalesced input, invalid lengths, incomplete headers and payloads, concurrent writers, bounded queues, client address routing, receive timestamps, malformed-client isolation, disconnects, reconnects, shutdown unblocking, and the dependency-free client adapter's connect/read/close lifecycle. The tests do not launch Minecraft or verify audio playback, mod loading, or performance under real-world network congestion.
