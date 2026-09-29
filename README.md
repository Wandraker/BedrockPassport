# BedrockPassport

BedrockPassport gives Bedrock players persistent Java-side server identities before backend login when joining through Geyser and Floodgate.

A Bedrock/Xbox account can save multiple Java-style usernames in a native Bedrock selector. The selected username and matching offline UUID are applied before the backend player is created, so the server sees that Java identity from the beginning of the session.

BedrockPassport is **not** an authentication plugin. Saved Passport entries are identity shortcuts, not ownership claims. Passwords, `/login`, `/register`, PINs, 2FA and account ownership remain the responsibility of the server's authentication layer.

## Requirements

- Paper, Purpur or Leaf
- Minecraft 26.2
- Java 25 or newer
- Geyser-Spigot
- Floodgate
- `online-mode=false`
- SkinsRestorer is optional

BedrockPassport performs a startup capability check for the Geyser pre-backend bridge it needs. If a required integration point is unavailable, the plugin fails closed instead of allowing an incomplete identity handoff.

Folia support is **not currently claimed**.

## Player flow

First connection:

```text
Bedrock/Xbox XUID
        ↓
Geyser holding environment
        ↓
Choose a Java username
        ↓
BedrockPassport resolves the Java identity
        ↓
Floodgate backend login using that username + offline UUID
        ↓
Your normal authentication plugin
        ↓
Gameplay
```

Returning connection:

```text
Bedrock/Xbox XUID
        ↓
Geyser holding environment
        ↓
[ Onelsey ✓       ]
[ AnotherAccount  ]
[ ＋ Add account  ]
[ ⚙ Manage accounts ]
        ↓
selected Java identity
```

Removing an entry only removes the shortcut from that Bedrock Passport. It does not delete passwords, player data, inventory, permissions, statistics or logs.

Different Bedrock/Xbox accounts may save the same Java username. This is intentional: BedrockPassport selects an identity, while the authentication system decides who is allowed to use it.

## Java identity and UUID behavior

On an offline-mode server, BedrockPassport uses the standard deterministic offline UUID derived from the selected username.

A Java client and a Bedrock client using the same username can therefore resolve to the same server identity instead of creating separate playerdata simply because the client edition changed.

BedrockPassport refuses to start when `online-mode=true`. Selecting a Java username is not proof of ownership of the corresponding Microsoft/Java account, so online-mode support would require a different trust model.

## Authentication compatibility and trust

BedrockPassport briefly uses Floodgate's linked-profile transport during the handshake so the backend can be created with the selected Java username and UUID.

Before normal Bukkit authentication plugins make their login decision, BedrockPassport replaces that temporary linked view with an **untrusted Passport identity**:

- `getCorrectUsername()` remains the selected Java username
- `getCorrectUniqueId()` remains the selected Java/offline UUID
- the real Xbox username and XUID remain available
- device and input information remain the real Bedrock values
- `isLinked()` is `false`
- `getLinkedPlayer()` is `null`

This prevents a saved Passport entry from being presented as proof that the Bedrock user owns that Java/server account.

### AuthMeReloaded

AuthMe remains a separate authentication layer. BedrockPassport performs the identity handoff first, then AuthMe can perform its normal password or registration flow.

No dedicated BedrockPassport ↔ AuthMe adapter is required.

### AlixSystem

`plugin.yml` orders BedrockPassport before AlixSystem so the untrusted Floodgate identity view is installed before Alix makes its authentication decision.

Alix compatibility still depends on the Alix build being compatible with the installed Geyser version. BedrockPassport does not patch another plugin's classloader or internal Geyser compatibility code.

## Bedrock detection and anti-cheats

Changing the Java-side identity does **not** turn a Bedrock connection into a Java client.

The real Bedrock/Xbox context remains available through Floodgate/Geyser. Bedrock-aware plugins should use the official APIs instead of relying only on username prefixes or assumptions about raw UUID formats.

BedrockPassport does not claim compatibility with integrations that identify Bedrock players only through a specific username prefix or another implementation detail.

## Session protection

With `security.first-session-wins: true`, the connection that already owns or has reserved an identity keeps it.

BedrockPassport protects several race conditions:

- Bedrock cannot select an identity that is already online
- Java cannot replace an active Bedrock Passport session using the same identity
- a Java login that already passed the protected pre-login gate is temporarily reserved
- two Bedrock connections cannot race the same Java identity through the Passport selector
- the same XUID cannot create two simultaneous Passport flows

Java-to-Java duplicate-login behavior remains the responsibility of the server and its authentication stack.

If a Bedrock session disconnects before Bukkit `PlayerJoinEvent` — including during an authentication plugin's pre-join/configuration flow — BedrockPassport listens to the Geyser session lifecycle and immediately clears unfinished Passport state.

The configured pending-reservation timeout remains as a safety-net expiry for abnormal cases where no disconnect event is observed.

## Skin handling

Skin handling is separate from account trust.

Default:

```yaml
skins:
  policy: preserve
```

With `preserve`:

- an existing meaningful skin can be preserved
- Floodgate's synthetic placeholder skin is not treated as a real player skin
- when no meaningful current skin exists, the normal Floodgate skin pipeline can continue
- SkinsRestorer can provide a stored skin when installed

When SkinsRestorer is available, BedrockPassport also keeps a post-join restore safety net for Passport-selected identities. After the selected Java identity reaches Bukkit join, BedrockPassport asks the public SkinsRestorer API for the skin stored for that Java UUID and reapplies it when present.

This covers reconnects and identity switches where the earlier Floodgate skin timing alone would not restore the saved Java skin.

Available policies:

- `preserve` — keep an existing meaningful skin; otherwise allow the normal Floodgate/SkinsRestorer result
- `refresh` — allow the final Floodgate/SkinsRestorer skin to replace the current one
- `off` — disable BedrockPassport skin handling

SkinsRestorer is optional and runtime-discovered. BedrockPassport does not depend on its internal implementation classes.

## Name matching

With:

```yaml
security:
  case-insensitive-bedrock-names: true
```

`Onelsey`, `onelsey` and `ONSELSEY` are treated as the same saved entry inside one Passport and as the same Bedrock-side session target.

The original saved username remains the Java identity used for UUID resolution. This setting does not replace the authentication plugin's own username rules.

## Holding environment and timeouts

The Passport selector can remain open longer than a normal Java login connection would normally allow.

While identity selection is active, BedrockPassport temporarily suspends the relevant read timeouts used by:

- the Geyser/MCProtocolLib downstream connection
- the Paper-compatible backend login channel

The original timeout values are restored before normal backend login continues.

BedrockPassport's own inactivity timeout remains in control while the selector is open.

## Configuration migration

BedrockPassport uses a versioned configuration schema and migrates supported older configurations automatically.

Updates preserve administrator values where possible, add newly introduced settings and migrate known legacy keys. Custom UI text is kept when possible.

You should not normally need to delete `config.yml` when updating.

## Administration

Permission: `bedrockpassport.admin` (OP by default).

```text
/bedrockpassport status
/bedrockpassport reload
/bedrockpassport who <javaName>
```

Alias: `/bp`.

### `/bp status`

Shows runtime state, active Passport selectors, tracked sessions, pending admissions, identity trust mode, skin policy and configuration schema.

### `/bp who <javaName>`

Shows whether a Java identity is online or pending. For an active Bedrock identity it can also show the Xbox gamertag, XUID, Java UUID and connection state.

### `/bp reload`

Reloads and migrates the configuration and rebuilds the BedrockPassport runtime without a full server restart.

Reload is refused while an identity selector or protected login reservation is still active.

## Installation

1. Install Geyser-Spigot and Floodgate.
2. Configure Geyser to use Floodgate authentication.
3. Put BedrockPassport in the server's `plugins` directory.
4. Start the server.

Passport identity shortcuts are stored in:

```text
plugins/BedrockPassport/passport.db
```

The database uses SQLite and supports migration from supported older schemas. Back it up with the rest of your persistent server data.

## Default configuration

```yaml
config-version: 2

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

skins:
  policy: preserve

form:
  title: '§l§bBedrockPassport'
  text: "§fChoose the Java account you want to use.\n§7Authentication still happens on the server after this step."
  input-label: '§bJava username'
  input-placeholder: 'Example: Onelsey'
  invalid-name: 'Use 3-16 characters: A-Z, a-z, 0-9 and _.'
  name-taken: 'That account is already saved in this Passport.'
  limit-reached: 'Your Bedrock Passport has reached its account limit.'
  account-in-use: 'That server account is already online.'
  passport-in-use: 'This Bedrock/Xbox account already has a pending Passport session.'
  internal-error: 'BedrockPassport could not save your account. Please reconnect.'
  timeout: 'You did not choose an account in time. Reconnect and try again.'

selector:
  title: '§l§bBedrockPassport'
  text: "§fChoose your server account.\n§7The last used account is marked with §a✓§7."
  last-used-suffix: ' §a✓'
  add-account: '§a＋ Add account'
  manage-accounts: '§e⚙ Manage accounts'

manage:
  title: '§l§bPassport accounts'
  text: "§fManage saved account shortcuts.\n§7Removing one does not delete server data or passwords."
  remove-prefix: '§c✕ '
  back: '§b← Back'
  confirm-title: '§l§cRemove account'
  confirm-text: "§fRemove %account% from this Bedrock Passport?\n§7Server data and passwords are not deleted."
  confirm-button: '§cRemove'
  cancel-button: '§bCancel'

compatibility:
  holding-world-init-timeout-seconds: 10
  suspend-geyser-downstream-read-timeout: true
  suspend-server-login-read-timeout: true
  form-transition-delay-millis: 250
```

Set `identity.max-accounts-per-xuid` to `0` or a negative value for no Passport-side account-count limit.

## Compatibility and maintenance

BedrockPassport targets Paper, Purpur and Leaf and does not depend on Minecraft NMS.

The pre-backend holding flow currently uses a small capability-checked bridge to Geyser pending-session internals because the public Geyser API does not expose all of the control BedrockPassport needs before Java backend login completes.

That bridge is intentionally checked at startup so incompatible Geyser changes fail visibly rather than silently producing a partial identity handoff.

## License

BedrockPassport is source-available software. See `LICENSE` for permitted use, redistribution, modification and contribution terms.
