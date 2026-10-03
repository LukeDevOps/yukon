---
status: accepted
---

# A dependency's location shows the home folder as a tilde

Decided on 2026-10-03, in the data-handling pass before the hosted service's privacy policy goes live.

A dependency's `location` is for display only (ADR 0030): the jar's path, or its entry in a Spring Boot fat jar. A jar under the user's home folder, such as one in `~/.m2` or `~/.gradle`, sent a path like `/Users/alice/.m2/repository/...`, which names the person running the agent. That happens mostly on a developer's own machine, and the server keeps it until the customer leaves.

## The design

- `DependencyRegistry.register` stores the location with a leading `user.home` written as `~`, so the registry and the wire only ever hold `~/.m2/repository/...`. Every location, from the startup listing and from the sweep at load, passes through it.
- Only a whole leading folder matches, followed by `/` or `\`, so `/home/al` leaves `/home/alice/x.jar` alone. On Windows the match ignores case, as its paths do.
- A blank or root home folder changes nothing, and neither does a location outside it.
- Where the bytes live (`DependencyOrigin`) keeps the real path. It never leaves the agent, and matching a loaded class to its jar needs it.

## Considered options

- Sending only the file name. Rejected: the folder shows where a jar came from, a build cache, a fat jar or a system folder, which helps when a dependency looks wrong.
- Writing the home folder as `~` in the collector. Rejected: the collector runs in the customer's network, so the agent's own payload, its logs and any other collector would still carry the name.

## Consequences

- A path under another user's home folder, as when one user runs a jar from another's, still names that user. That is rare and outside what the agent can know.
