# BedrockPassport

BedrockPassport gives Bedrock players persistent Java-side identities when they join a Java server through Geyser and Floodgate.

A Bedrock/Xbox account can keep several server accounts in one passport. On every Bedrock connection, the player is held in Geyser's pre-backend world and gets a small account selector before the Java server login begins. The last-used account is shown first, so a normal reconnect takes one tap. Players can also add another account or remove an old binding from the passport.

The selected username and UUID are supplied to Floodgate before the backend player is created. Paper, Purpur, Leaf, authentication plugins, permissions plugins, logging plugins, player data, statistics, and other server systems therefore see the selected Java identity from the start of the session rather than a cosmetic display name.

BedrockPassport does not implement passwords, `/login`, `/register`, or account authentication. Existing authentication plugins continue handling existing and new accounts after BedrockPassport selects the identity.

## Requirements

- Paper, Purpur, or Leaf
- Minecraft 26.2
- Java 25 or newer
- Geyser-Spigot on the same server
- Floodgate on the same server
- `online-mode=false`

The current development baseline is Geyser 2.11.3-SNAPSHOT b1247 and Floodgate 2.2.5-SNAPSHOT b141. BedrockPassport checks the Geyser pending-session bridge when it starts and disables itself if the required integration points are unavailable.

## Account flow

First connection:

```text
Bedrock/Xbox XUID
        ↓
Geyser holding world
        ↓
Choose a Java username
        ↓
XUID ↔ server account
        ↓
Floodgate backend login
        ↓
Your normal authentication plugin
```

Returning connection:

```text
Bedrock/Xbox XUID
        ↓
Geyser holding world
        ↓
[ Onelsey (last used) ]
[ AnotherAccount       ]
[ + Add account        ]
[ Manage accounts      ]
        ↓
selected real Java identity
        ↓
Floodgate backend login
```

Removing an account in BedrockPassport only removes the XUID-to-account binding. It does not delete passwords, authentication records, player data, inventory, statistics, permissions, logs, or any other server-side data.

## UUID behavior

BedrockPassport treats the selected server account as the identity and the Xbox XUID as the passport that can access it.

The selected account uses the same deterministic offline UUID as a Java client with the same username. This means a Java client and a Bedrock client using the same server account resolve to the same UUID instead of appearing as two separate players with the same name.

The current version intentionally refuses to start when `online-mode=true`. Choosing a Java username is not proof of ownership of a Mojang/Microsoft Java account, so BedrockPassport does not spoof an authenticated online-mode identity. Verified online-mode account linking can be added separately later without weakening the offline authentication model.

## Authentication and security

BedrockPassport is an identity selector, not an authentication system.

On an offline-mode server, use a normal authentication plugin if server accounts need password protection. A Bedrock player selecting an existing username is not proof that they own that account; the authentication plugin is responsible for accepting or rejecting the login.

A BedrockPassport username can only be bound to one Xbox XUID at a time. Username comparisons are case-insensitive.

BedrockPassport currently uses Floodgate's linked-player handshake override to provide the selected username and UUID before backend login. If your authentication plugin has an option that automatically trusts or auto-logs-in every Floodgate `isLinked()` player, disable that option unless you intentionally want XUID ownership alone to authorize the selected server account. For password-protected offline accounts, the authentication plugin must still require its normal proof of ownership.

## Installation

1. Install Geyser-Spigot and Floodgate.
2. Configure Geyser to use Floodgate authentication.
3. Put BedrockPassport in the server `plugins` directory.
4. Start the server.

BedrockPassport stores its mappings in:

`plugins/BedrockPassport/passport.db`

Back up this file with the rest of your server data.

Existing BedrockPassport 0.5.x single-account databases are migrated automatically to the multi-account schema.

## Configuration

```yaml
identity:
  inactivity-timeout-seconds: 60
  max-accounts-per-xuid: 3
  min-name-length: 3
  max-name-length: 16
  name-pattern: '^[A-Za-z0-9_]+$'
form:
  title: 'BedrockPassport'
  text: 'Choose the Java username you want to use on this server. Server authentication will still handle login or registration after you connect.'
  input-label: 'Java username'
  input-placeholder: 'Example: Onelsey'
  invalid-name: 'Use 3-16 characters: A-Z, a-z, 0-9 and _.'
  name-taken: 'That username is already assigned to another Bedrock Passport.'
  limit-reached: 'Your Bedrock Passport has reached its account limit.'
  internal-error: 'BedrockPassport could not save your account. Please reconnect.'
  timeout: 'BedrockPassport selection timed out. Reconnect and try again.'
selector:
  title: 'BedrockPassport'
  text: 'Choose the server account you want to use.'
  last-used-suffix: '  (last used)'
  add-account: '+ Add account'
  manage-accounts: 'Manage accounts'
manage:
  title: 'BedrockPassport accounts'
  text: 'Removing an account only removes it from this Bedrock Passport. Server data and authentication records are not deleted.'
  remove-prefix: 'Remove: '
  back: 'Back'
  confirm-title: 'Remove account'
  confirm-text: 'Remove %account% from this Bedrock Passport? Server data and passwords are not deleted.'
  confirm-button: 'Remove from Passport'
  cancel-button: 'Cancel'
compatibility:
  holding-world-init-timeout-seconds: 10
```

Set `identity.max-accounts-per-xuid` to `0` or a negative value for no BedrockPassport-side account-count limit.

## Compatibility note

BedrockPassport does not depend on Paper, Purpur, or Leaf internals. Its early identity flow is implemented around Geyser and Floodgate.

The pre-backend holding flow uses a small capability-checked bridge to Geyser's pending session internals because the public Geyser API does not currently expose pending sessions before Java backend login completes. If a future Geyser update changes those internals, BedrockPassport should fail during startup rather than silently falling back to a cosmetic nickname.

## License

BedrockPassport is source-available software. See `LICENSE` for permitted use, redistribution, modification, and contribution terms.
