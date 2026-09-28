# BedrockPassport

BedrockPassport gives Bedrock players persistent Java-side identities when they join a Java server through Geyser and Floodgate.

A Bedrock/Xbox account can save several Java usernames in a small pre-login account selector. The selected username and offline UUID are supplied to Floodgate before the backend player is created, so Paper, Purpur, Leaf and normal server plugins see the chosen Java identity from the start of the session.

BedrockPassport is not an authentication plugin. It does not implement passwords, `/login` or `/register`. Saved accounts are convenience shortcuts, not proof of account ownership. Your normal authentication plugin remains responsible for deciding whether the player may use the selected account.

## Requirements

- Paper, Purpur, or Leaf
- Minecraft 26.2
- Java 25 or newer
- Geyser-Spigot on the same server
- Floodgate on the same server
- `online-mode=false`

The current development baseline is Geyser 2.11.3-SNAPSHOT b1247 and Floodgate 2.2.5-SNAPSHOT b141. BedrockPassport checks the required Geyser pending-session integration when it starts and fails closed if it is not compatible.

## Player flow

First connection:

```text
Bedrock/Xbox XUID
        ↓
Geyser holding world
        ↓
Choose a Java username
        ↓
Floodgate backend login using that Java name and UUID
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
selected Java identity
```

Removing an entry only removes it from that Bedrock Passport. It does not delete passwords, player data, inventory, permissions, statistics, logs, or any other server data.

The same Java username may be saved by more than one Bedrock/Xbox account. This is intentional: BedrockPassport is an account selector, not an ownership registry. The authentication plugin decides who actually knows the credentials for that server account.

## UUID behavior

On an offline-mode server, BedrockPassport uses the same deterministic offline UUID as a Java client with the same username. A Java client and a Bedrock client selecting `Onelsey` therefore resolve to the same server identity instead of two different UUIDs with the same name.

BedrockPassport currently refuses to start when `online-mode=true`. Selecting a username is not sufficient proof of ownership of a Mojang/Microsoft Java account.

## Session protection

With `security.first-session-wins: true`, the connection that already owns or has reserved an identity keeps it.

- Bedrock selecting an account that is already online stays in the Passport flow and receives an account-in-use message.
- Java trying to log in with an identity currently used by BedrockPassport is rejected before Paper can replace the existing Bedrock session.
- A Java login that already passed pre-login is temporarily reserved so Bedrock cannot race it before the Java player joins.
- Two Bedrock connections cannot race the same Java identity through the Passport gate.
- A second connection from the same XUID cannot create another simultaneous Passport flow.
- Java-to-Java duplicate-login behavior remains the responsibility of the server and its authentication stack.

Pending reservations expire automatically after `security.pending-reservation-seconds`.

## Name matching

`security.case-insensitive-bedrock-names: true` makes BedrockPassport treat case variants such as `Onelsey`, `onelsey` and `ONSELSEY` as the same saved entry inside one Passport and as the same Bedrock-side session target.

This setting does not replace the Java authentication plugin's own username policy.

If a single Passport already contains conflicting case variants and case-insensitive mode is enabled, BedrockPassport refuses to start instead of silently merging entries.

## Holding-world timeout

The Passport selector can remain open longer than the default 30-second Java login timeout. BedrockPassport temporarily suspends both relevant read timeouts only while the Passport flow is active:

- Geyser/MCProtocolLib downstream `read-timeout`
- Paper-compatible server login-channel `ReadTimeoutHandler`

Both original timeout values are restored before normal backend login continues. The Passport inactivity timeout is therefore authoritative during account selection.

## Configuration updates

BedrockPassport uses `config-version` and migrates `config.yml` automatically.

On update it preserves existing administrator values, adds newly introduced settings, migrates the legacy `compatibility.suspend-backend-read-timeout` value to the two replacement settings when present, and then saves the updated file.

You no longer need to delete `config.yml` just to receive new options.

## Administration

Permission: `bedrockpassport.admin` (OP by default).

```text
/bedrockpassport status
/bedrockpassport reload
/bedrockpassport who <javaName>
```

`/bedrockpassport status` shows runtime state, active Passport selectors, tracked login sessions and config schema.

`/bedrockpassport who <javaName>` shows whether that Java identity is currently online or pending. For an active Bedrock identity it also shows the Xbox gamertag and XUID.

`/bedrockpassport reload` reloads the config and rebuilds the Passport runtime without a server restart. Reload is refused while a player is currently inside the Passport selector or while a protected login reservation is still pending, so an in-progress identity flow is never torn down underneath them.

## Installation

1. Install Geyser-Spigot and Floodgate.
2. Configure Geyser to use Floodgate authentication.
3. Put BedrockPassport in the server `plugins` directory.
4. Start the server.

BedrockPassport stores account shortcuts in:

`plugins/BedrockPassport/passport.db`

Back up this file together with the rest of your server data.

Existing BedrockPassport 0.5.x and 0.6.x databases are migrated automatically. The 0.9 schema removes the old global XUID ownership of a Java username: uniqueness is now scoped to one Passport (`XUID + name`), so one Bedrock account cannot permanently reserve a server username away from everybody else.

## Configuration

```yaml
config-version: 1
identity:
  inactivity-timeout-seconds: 60
  max-accounts-per-xuid: 3
  min-name-length: 3
  max-name-length: 16
  name-pattern: '^[A-Za-z0-9_]+$'
security:
  first-session-wins: true
  case-insensitive-bedrock-names: true
  duplicate-login-message: 'This server account is already online.'
  pending-reservation-seconds: 45
form:
  title: 'BedrockPassport'
  text: 'Choose the Java username you want to use on this server. Server authentication will still handle login or registration after you connect.'
  input-label: 'Java username'
  input-placeholder: 'Example: Onelsey'
  invalid-name: 'Use 3-16 characters: A-Z, a-z, 0-9 and _.'
  name-taken: 'That account is already saved in this Passport.'
  limit-reached: 'Your Bedrock Passport has reached its account limit.'
  account-in-use: 'That server account is already online.'
  passport-in-use: 'This Bedrock/Xbox account already has a pending Passport session.'
  internal-error: 'BedrockPassport could not save your account. Please reconnect.'
  timeout: 'You did not choose an account in time. Reconnect and try again.'
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
  suspend-geyser-downstream-read-timeout: true
  suspend-server-login-read-timeout: true
  form-transition-delay-millis: 250
```

Set `identity.max-accounts-per-xuid` to `0` or a negative value for no Passport-side account-count limit.

## Compatibility note

BedrockPassport does not depend on Paper, Purpur, or Leaf NMS. The pre-backend holding flow uses a small capability-checked bridge to Geyser pending-session internals because the public Geyser API does not currently expose pending sessions before Java backend login completes.

## License

BedrockPassport is source-available software. See `LICENSE` for permitted use, redistribution, modification, and contribution terms.
