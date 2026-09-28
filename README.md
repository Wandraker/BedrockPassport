# BedrockPassport

BedrockPassport gives Bedrock players persistent Java-side identities when they join a Java server through Geyser and Floodgate.

A Bedrock/Xbox account can save several Java usernames in a pre-login selector. The selected username and offline UUID are supplied to Floodgate before the backend player is created, so Paper, Purpur, Leaf and normal server plugins see the chosen Java identity from the beginning of the session.

BedrockPassport is **not** an authentication plugin. Saved accounts are convenience shortcuts, not ownership claims. Passwords, `/login`, `/register` and account ownership remain the responsibility of your normal authentication plugin.

## Requirements

- Paper, Purpur or Leaf
- Minecraft 26.2
- Java 25 or newer
- Geyser-Spigot
- Floodgate
- `online-mode=false`
- SkinsRestorer is optional

The 1.0.2 compatibility baseline is Geyser 2.11.3-SNAPSHOT b1247 and Floodgate 2.2.5-SNAPSHOT b141. BedrockPassport checks the Geyser pending-session bridge at startup and fails closed if a required integration point is unavailable.

## Player flow

First connection:

```text
Bedrock/Xbox XUID
        ↓
Geyser holding world
        ↓
Choose a Java username
        ↓
Floodgate backend login using that Java name + offline UUID
        ↓
Your normal authentication plugin
```

Returning connection:

```text
Bedrock/Xbox XUID
        ↓
Geyser holding world
        ↓
[ Onelsey ✓       ]
[ AnotherAccount  ]
[ ＋ Add account  ]
[ ⚙ Manage accounts ]
        ↓
selected Java identity
```

Removing an entry only removes the shortcut from that Bedrock Passport. It does not delete passwords, player data, inventory, permissions, statistics or logs.

Different Bedrock/Xbox accounts may save the same Java username. This is intentional. BedrockPassport does not decide who owns a server account; the authentication system does.

## UUID behavior

On an offline-mode server, BedrockPassport uses the same deterministic offline UUID as a Java client with the same username. A Java client and a Bedrock client selecting `Onelsey` therefore resolve to the same server identity instead of two different UUIDs with the same name.

BedrockPassport currently refuses to start when `online-mode=true`. Selecting a username is not sufficient proof of ownership of a Mojang/Microsoft Java account.


## Authentication compatibility and trust

BedrockPassport uses Floodgate's linked-profile transport only briefly inside the Floodgate handshake so the backend can be created with the selected Java name and offline UUID. Before Bukkit authentication plugins handle the login, BedrockPassport replaces the Floodgate player view with an untrusted Passport identity:

- `getCorrectUsername()` remains the selected Java username;
- `getCorrectUniqueId()` remains the selected Java/offline UUID;
- Xbox username, XUID, device and input information remain the real Bedrock values;
- `isLinked()` is `false`;
- `getLinkedPlayer()` is `null`.

This is important because a saved Passport shortcut is not proof that the Bedrock user owns the selected Java/server account. Authentication plugins must still require their normal password, registration, PIN, form or other proof.

`plugin.yml` orders BedrockPassport before AlixSystem so the untrusted Floodgate view is installed at the earliest Bukkit pre-login priority before Alix's login decision. AuthMe requires no special adapter; it can continue its own authentication after the Passport identity handoff.

The public AlixSystem 3.10.0 build has a separate compatibility issue with Geyser 2.11.x in its virtual-limbo Bedrock detection: that build can reference the old `org.geysermc.geyser.network.netty.ChannelWrapper` class. Current AlixSystem source checks the newer `org.geysermc.geyser.network.java.ChannelWrapper` first. BedrockPassport cannot safely patch another plugin's classloader, so an Alix build containing that upstream fix is still required.

### Anti-cheat detection

The untrusted handoff does not turn the player into a Java client. Floodgate still stores the player as a Bedrock/Floodgate player and can resolve the selected Java UUID through `FloodgateApi.isFloodgatePlayer(...)`. After backend login, Geyser also indexes the session by the backend Java UUID. Anti-cheats that use the official Floodgate/Geyser APIs therefore continue to identify Passport players as Bedrock. Detection based only on a username prefix or on a raw Floodgate UUID is not a supported compatibility contract.

## Session protection

With `security.first-session-wins: true`, the connection that already owns or has reserved an identity keeps it.

- Bedrock selecting an identity that is already online stays in the Passport flow and receives an account-in-use message.
- Java trying to log in with an identity currently used by BedrockPassport is rejected before Paper can replace the existing Bedrock session.
- A Java login that already passed the protected pre-login gate is temporarily reserved so Bedrock cannot race it before join.
- Two Bedrock connections cannot race the same Java identity through the Passport gate.
- A second connection from the same XUID cannot create another simultaneous Passport flow.
- Java-to-Java duplicate-login behavior remains the responsibility of the server and its authentication stack.

Geyser disconnects release pending Bedrock reservations immediately, including disconnects during an authentication plugin's pre-join/configuration flow. `security.pending-reservation-seconds` remains a safety-net expiry for abnormal cases where no disconnect event is observed.

## Skin handling

BedrockPassport 1.0.2 keeps skin handling separate from account trust and adds a post-join SkinsRestorer restore safety net for Passport-selected identities. The selected Java identity is no longer exposed to authentication plugins as a trusted Floodgate account link.

The default policy is:

```yaml
skins:
  policy: preserve
```

`preserve` works at the Floodgate `SkinApplyEvent` after other skin listeners have had a chance to run:

- if the player profile already has a meaningful skin, BedrockPassport preserves it;
- Floodgate's synthetic default Java placeholder skin is not treated as a real player skin;
- if there is no meaningful current skin, BedrockPassport allows the final skin from the normal Floodgate pipeline to apply;
- when SkinsRestorer is installed, its Floodgate listener may provide the selected Java identity's stored/premium/default skin before BedrockPassport makes the final preserve/apply decision;
- after a Passport-selected player reaches Bukkit join, BedrockPassport asks the public SkinsRestorer API for the skin explicitly stored for that selected Java UUID and re-applies it when present, covering reconnects where the earlier Floodgate skin timing did not restore it;
- when SkinsRestorer has no replacement skin, the incoming Bedrock/Xbox skin can be used as the fallback;
- SkinsRestorer is optional; the compatibility bridge is runtime-discovered and BedrockPassport does not depend on SkinsRestorer internal implementation classes.

Available policies:

- `preserve` — keep an existing skin; otherwise allow the final Floodgate/SkinsRestorer skin. Recommended.
- `refresh` — always allow the final Floodgate/SkinsRestorer skin to replace the current one.
- `off` — BedrockPassport does not alter Floodgate skin event cancellation.

SkinsRestorer 15.12.6 is part of the 1.0.2 integration baseline, but it is not required.

## Name matching

`security.case-insensitive-bedrock-names: true` treats `Onelsey`, `onelsey` and `ONSELSEY` as the same saved entry inside one Passport and as the same Bedrock-side session target.

This does not replace the Java authentication plugin's username rules.

## Holding-world timeout

The Passport selector can stay open longer than the normal 30-second Java login timeout. While the Passport flow is active, BedrockPassport temporarily suspends both relevant read timeouts:

- Geyser/MCProtocolLib downstream `read-timeout`
- Paper-compatible server login-channel `ReadTimeoutHandler`

Both original timeout values are restored before normal backend login continues. The Passport inactivity timeout is therefore authoritative during account selection.

## Configuration migration

BedrockPassport uses `config-version` and migrates `config.yml` automatically.

Updates preserve administrator values, add newly introduced settings and migrate known legacy keys. When upgrading from the old unstyled 0.9 defaults, untouched UI strings are upgraded to the 1.0 colored Bedrock form style while custom text remains unchanged.

You do not need to delete `config.yml` to receive new options.

## Administration

Permission: `bedrockpassport.admin` (OP by default).

```text
/bedrockpassport status
/bedrockpassport reload
/bedrockpassport who <javaName>
```

Aliases: `/bp`.

`/bp status` shows runtime state, active Passport selectors, tracked sessions, pending admissions, skin policy and config schema.

`/bp who <javaName>` shows whether that Java identity is online or pending. For an active Bedrock identity it also shows the Xbox gamertag and XUID.

`/bp reload` reloads and migrates the configuration and rebuilds the runtime without restarting the server. Reload is refused while a player is inside the Passport selector or while a protected login reservation is still pending.

## Installation

1. Install Geyser-Spigot and Floodgate.
2. Configure Geyser to use Floodgate authentication.
3. Put BedrockPassport in `plugins`.
4. Start the server.

BedrockPassport stores account shortcuts in:

```text
plugins/BedrockPassport/passport.db
```

Back up this file with the rest of your server data.

Existing BedrockPassport 0.5.x, 0.6.x and 0.9.x databases are migrated automatically.

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

## Compatibility note

BedrockPassport targets Paper, Purpur and Leaf and does not depend on their NMS internals. It does not currently claim Folia support. The pre-backend holding flow uses a small capability-checked bridge to Geyser pending-session internals because the public Geyser API does not expose pending sessions before Java backend login completes.

## License

BedrockPassport is source-available software. See `LICENSE` for permitted use, redistribution, modification and contribution terms.
