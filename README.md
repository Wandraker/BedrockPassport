# BedrockPassport

BedrockPassport gives Bedrock players persistent Java-side identities before backend login when joining through Geyser.

A Bedrock/Xbox account can keep multiple selectable identities. BedrockPassport automatically uses the identity model that matches the backend:

- `online-mode=false` → `LOCAL`: saved server usernames with deterministic offline UUIDs, handed to the backend through Floodgate
- `online-mode=true` → `JAVA_ACCOUNT`: verified Microsoft/Minecraft Java accounts with their real Java username and UUID

The selector runs before the Java backend player is created, so inventory, permissions, statistics, claims and other UUID-bound server data belong to the selected identity from the beginning of the session.

In `LOCAL` mode BedrockPassport is **not** the authentication layer: saved names are identity shortcuts and a server authentication plugin still decides who may use them. In `JAVA_ACCOUNT` mode the selected Java account is verified through Microsoft's/Minecraft's authentication flow before the online-mode backend connection is created.

## Requirements

- Paper, Purpur, Leaf or Folia
- Minecraft 26.2
- Java 25 or newer
- Geyser-Spigot
- SkinsRestorer is optional

### LOCAL mode

- `online-mode=false`
- Floodgate
- Geyser using Floodgate authentication

### JAVA_ACCOUNT mode

- `online-mode=true`
- Geyser Java authentication is forced to `online` while this runtime is active
- Floodgate is not required for the Java-account handoff and may remain installed
- When Floodgate is installed on the same server, BedrockPassport temporarily suspends its server packet injector in JAVA_ACCOUNT mode so a real online Java login is not interpreted as a Floodgate login

BedrockPassport performs a startup capability check for the Geyser pre-backend bridge it needs. If a required integration point is unavailable, the plugin fails closed instead of allowing an incomplete identity handoff.

Folia support is implemented in the 1.2.0 development line. BedrockPassport uses Paper/Folia global and entity schedulers instead of the legacy Bukkit main-thread scheduler. Runtime verification on a real Folia server is still required before the 1.2.0 release is considered validated.

Geyser, Floodgate, authentication plugins and optional integrations used alongside BedrockPassport must also support Folia; BedrockPassport cannot make an unrelated plugin region-thread safe.

## Player flow

### LOCAL mode

First connection:

```text
Bedrock/Xbox XUID
        ↓
Geyser holding environment
        ↓
Choose a server username
        ↓
BedrockPassport resolves the offline Java identity
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
selected LOCAL identity
```

### JAVA_ACCOUNT mode

First connection:

```text
Bedrock/Xbox XUID
        ↓
BedrockPassport selector
        ↓
＋ Add Java account
        ↓
Microsoft device-code authentication through Geyser
        ↓
verified Java username + real Java UUID
        ↓
online-mode backend login as that Java account
```

Returning connection:

```text
Bedrock/Xbox XUID
        ↓
BedrockPassport selector
        ↓
select saved Java account
        ↓
encrypted saved auth chain is refreshed
        ↓
online-mode backend login as the same real Java profile
```

Removing an entry only removes it from that Bedrock Passport. It does not delete Java/Microsoft accounts, passwords, player data, inventory, permissions, statistics or logs.

Different Bedrock/Xbox accounts may save the same LOCAL username. This is intentional: in LOCAL mode BedrockPassport selects an identity while the server authentication system decides who is allowed to use it. JAVA_ACCOUNT entries are tied to the verified Java UUID returned by Microsoft/Minecraft authentication.

## Java identity and UUID behavior

On an offline-mode server, BedrockPassport uses the standard deterministic offline UUID derived from the selected username.

A Java client and a Bedrock client using the same username can therefore resolve to the same server identity instead of creating separate playerdata simply because the client edition changed.

The stable LOCAL provider uses the standard offline UUID model and does not treat a selected name as proof of ownership.

The JAVA_ACCOUNT provider is different: BedrockPassport asks Geyser's Microsoft/Minecraft authentication stack to verify the Java account, stores the real Java UUID/name and reconnects using a refreshed authenticated Java profile. The two providers intentionally remain separate.

## Authentication compatibility and trust

The trust model depends on the active provider.

### LOCAL trust model

BedrockPassport briefly uses Floodgate's linked-profile transport during the handshake so the backend can be created with the selected Java username and UUID.

Before normal Bukkit authentication plugins make their login decision, BedrockPassport replaces that temporary linked view with an **untrusted Passport identity**:

- `getCorrectUsername()` remains the selected Java username
- `getCorrectUniqueId()` remains the selected Java/offline UUID
- the real Xbox username and XUID remain available
- device and input information remain the real Bedrock values
- `isLinked()` is `false`
- `getLinkedPlayer()` is `null`

This prevents a saved Passport entry from being presented as proof that the Bedrock user owns that Java/server account.

### JAVA_ACCOUNT trust model

JAVA_ACCOUNT entries are created only after Microsoft/Minecraft authentication succeeds. BedrockPassport stores the verified Java username and UUID and uses the refreshed authenticated Java profile for the online-mode backend connection.

The saved authentication chain is encrypted with AES-GCM in `passport.db`; the encryption key is stored separately in `plugins/BedrockPassport/credentials.key`. Back up both together and treat both files as security-sensitive server data.

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
/bedrockpassport reset-login <javaName>
/bedrockpassport reset-login --all confirm
```

Alias: `/bp`.

### `/bp status`

Shows runtime state, active Passport selectors, tracked sessions, pending admissions, the active identity provider, identity trust mode, detected threading model, skin policy and configuration schema.

### `/bp who <javaName>`

Shows whether a Java identity is online or pending. For an active Bedrock identity it can also show the Xbox gamertag, XUID, Java UUID and connection state.

### `/bp reload`

Reloads and migrates the configuration and rebuilds the BedrockPassport runtime without a full server restart.

Reload is refused while an identity selector or protected login reservation is still active.

### `/bp reset-login <javaName>`

Deletes the saved reusable authentication state for the matching `JAVA_ACCOUNT` entry without deleting its Passport identity or UUID.

The next time that Java account is selected, the player must complete Microsoft/Minecraft authentication again. BedrockPassport accepts the re-authentication only when it resolves to the same saved Java UUID.

### `/bp reset-login --all confirm`

Deletes all saved `JAVA_ACCOUNT` authentication state and rotates `credentials.key`.

Passport identities remain in the database. Affected players verify their existing Java accounts once on their next connection, after which normal saved-account reuse continues.

The reset is refused while a JAVA_ACCOUNT selector is active so a concurrent login cannot immediately write a credential back during the reset.

These commands remove BedrockPassport's local saved sign-in state. They do not remotely revoke a credential that was already copied from a compromised server; use the Microsoft account's security controls as well if token theft is suspected.

## Installation

1. Install Geyser-Spigot.
2. For LOCAL mode, install Floodgate and use `online-mode=false`.
3. For JAVA_ACCOUNT mode, use `online-mode=true`; Floodgate may remain installed.
4. Put BedrockPassport in the server's `plugins` directory.
5. Start the server.

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


## Online-mode integration

With `online-mode=true`, BedrockPassport activates the `JAVA_ACCOUNT` provider automatically.

On integrated plugin platforms, Geyser can auto-select Floodgate authentication when Floodgate is installed. BedrockPassport reasserts Geyser's runtime Java authentication type as `online` for JAVA_ACCOUNT sessions.

Floodgate itself also installs a server-side Netty login handler. A verified Java login does not contain Floodgate player data, so BedrockPassport temporarily suspends Floodgate's removable Spigot packet injector while JAVA_ACCOUNT mode is active and restores it when the Passport runtime stops. Floodgate can therefore remain installed without intercepting verified online Java logins.

Saved Java authentication chains are kept in:

```text
plugins/BedrockPassport/passport.db
```

The AES-GCM encryption key is kept separately in:

```text
plugins/BedrockPassport/credentials.key
```

The first account connection requires Microsoft device-code authentication. Later connections can select the saved Java account and reuse a refreshed authenticated chain without repeating the device-code flow unless re-authentication is required.

Administrators can invalidate one saved Java sign-in with `/bp reset-login <javaName>`, or invalidate every saved JAVA_ACCOUNT sign-in and rotate the local encryption key with `/bp reset-login --all confirm`. The identity records and verified Java UUIDs are kept, so reset accounts re-authenticate against the same saved identity instead of being recreated.

## Compatibility and maintenance

BedrockPassport targets Paper, Purpur, Leaf and Folia and does not depend on Minecraft NMS.

For Folia, player-bound deferred work is dispatched through the player's entity scheduler, while plugin-wide reload work is dispatched through the global-region scheduler. Session tracking uses shared synchronized state and performs existing-player resynchronization on each player's owning scheduler after a runtime reload.

The build also performs a second Java compilation against the Folia 26.2 API in addition to the normal Paper API build, and rejects legacy Bukkit scheduler calls in BedrockPassport sources.

The pre-backend holding flow currently uses a small capability-checked bridge to Geyser pending-session internals because the public Geyser API does not expose all of the control BedrockPassport needs before Java backend login completes.

That bridge is intentionally checked at startup so incompatible Geyser changes fail visibly rather than silently producing a partial identity handoff.

## License

BedrockPassport is source-available software. See `LICENSE` for permitted use, redistribution, modification and contribution terms.
