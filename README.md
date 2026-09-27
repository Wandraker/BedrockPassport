# BedrockPassport

BedrockPassport gives Bedrock players a persistent Java username when they join a Java server through Geyser and Floodgate.

A Bedrock player chooses a Java-compatible username on the first connection. BedrockPassport binds that username to the player's Xbox XUID and reuses it automatically on later connections. The Java server receives the selected username as the player's real login identity rather than as a chat, display-name, or TAB-only nickname.

BedrockPassport does not implement passwords, `/login`, `/register`, or account authentication. If your server uses an authentication plugin, that plugin continues handling existing and new accounts after BedrockPassport resolves the username.

## Current requirements

- Paper, Purpur, or Leaf
- Minecraft 26.2
- Java 25 or newer
- Geyser-Spigot on the same server
- Floodgate on the same server

The current development baseline is Geyser 2.11.3-SNAPSHOT b1247 and Floodgate 2.2.5-SNAPSHOT b141. BedrockPassport performs a capability check when it starts and disables itself if the required Geyser pending-session bridge is unavailable.

## How it works

On the first Bedrock connection, Geyser keeps the client in its temporary holding world while BedrockPassport displays a username form. The player is not created as a Bukkit/Paper player under the temporary Xbox/Floodgate name.

After a valid username is selected, BedrockPassport stores the XUID-to-username mapping and supplies that identity to Floodgate during the login handshake. Floodgate then continues its normal login process and the server receives the selected username from the beginning of the player session.

On later connections, the stored identity is resolved immediately and no username form is shown.

## Installation

1. Install Geyser-Spigot and Floodgate normally.
2. Configure Geyser to use Floodgate authentication.
3. Put BedrockPassport in the server `plugins` directory.
4. Start the server.

BedrockPassport stores its data in:

`plugins/BedrockPassport/passport.db`

Back up this file together with the rest of your server data. Deleting it removes all Bedrock username bindings.

## Username rules

The default rules match ordinary Java usernames:

- 3 to 16 characters
- `A-Z`, `a-z`, `0-9`, and `_`
- one Bedrock/Xbox XUID can own one BedrockPassport username
- one BedrockPassport username cannot be assigned to two different XUIDs

The server's existing authentication system remains responsible for deciding whether a selected username represents an existing account, a new account, or requires authentication.

## Configuration

```yaml
identity:
  inactivity-timeout-seconds: 60
  min-name-length: 3
  max-name-length: 16
  name-pattern: '^[A-Za-z0-9_]+$'
form:
  title: 'BedrockPassport'
  text: 'Choose the Java username you want to use on this server. This choice is linked to your Bedrock/Xbox account.'
  input-label: 'Java username'
  input-placeholder: 'Example: Onelsey'
  invalid-name: 'Use 3-16 characters: A-Z, a-z, 0-9 and _.'
  name-taken: 'That username is already assigned to another Bedrock account.'
  internal-error: 'BedrockPassport could not save your username. Please reconnect.'
  timeout: 'BedrockPassport nickname selection timed out. Reconnect and try again.'
compatibility:
  holding-world-init-timeout-seconds: 10
```

## Compatibility note

BedrockPassport intentionally does not depend on Paper, Purpur, or Leaf internals. The early identity flow is implemented around Geyser and Floodgate, so the server implementation does not need a separate NMS adapter.

The first-login holding flow does use a small capability-checked bridge to Geyser's pending session internals because the public Geyser API does not currently expose pending sessions before the Java backend login is complete. An incompatible Geyser update should therefore fail at plugin startup instead of silently falling back to a cosmetic nickname.

## License

BedrockPassport is source-available software. See `LICENSE` for permitted use, redistribution, modification, and contribution terms.
