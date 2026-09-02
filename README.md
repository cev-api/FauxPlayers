# FauxPlayers

FauxPlayers adds display-only player entries to Minecraft.

It supports:

- Paper 1.21.x -> 26.2
- Fabric for Minecraft 26.2.
- Server status responses.
- Manipulating the in-game player list.
- Relaying players from another server or HTTP source.
- Simulated ping, skins, heads, and TAB scores.

The project has one shared core.
The build creates one Paper JAR and one Fabric JAR.

## Important

A fake player is a client display entry.
It is not a connected player.

FauxPlayers does not create:

- a connection;
- an entity;
- a login or quit event;
- a command sender;
- a Bukkit Player;
- a server-side player.

On Paper, `Bukkit.getOnlinePlayers()` contains only real players.
Fake entries do not change permissions, events, slots, limits, or server APIs.

Other players can see a fake entry like a normal player entry.
It can show a name, display name, skin, head, game mode, ping bars, and a TAB ping score.
The ping is simulated.

## Requirements

Build:

- Java 25.
- Gradle Wrapper.

Paper:

- Paper 1.21.x -> 26.2
- Java 21.
- ProtocolLib for Paper packet features.

Fabric:

- Minecraft 26.2.
- Fabric Loader 0.19.3 or later.
- Fabric API 0.154.2+26.2 or later.
- Java 25.

Optional:

- TAB for TAB integration on both platforms.

## Build

Windows:

~~~text
.\gradlew.bat build --no-daemon
~~~

Linux or macOS:

~~~text
./gradlew build --no-daemon
~~~

The build creates:

~~~text
paper/build/libs/FauxPlayers-1.0.0-paper.jar
fabric/build/libs/FauxPlayers-1.0.0-fabric.jar
~~~

Install only the JAR for your server platform.
Do not install both JARs on one server.
Restart the server after a JAR update.

The first start creates `config.yml`:

- Paper: `plugins/FauxPlayers/config.yml`.
- Fabric: `config/fauxplayers/config.yml`.

Use `/fauxplayers reload` after a configuration change.
The reload command does not load a new JAR.

## Server status

FauxPlayers can change the data sent to server-list clients.
It can change:

- the online count;
- the maximum count;
- the sample names.

The count and name sample are separate values.
An upstream server can report 30 players and provide five names.
FauxPlayers does not create the missing names.

STATUS data does not create connections.
It does not change the server login capacity.

## In-game player list

FauxPlayers sends player-info packets to real players.
The fake entries exist in the receiving client only.

The displayed latency controls the ping bars.
It also controls the TAB `Ping: N` score.
Random ping can update both values at the configured interval.

To show the TAB score, enable the TAB player-list objective:

~~~yaml
playerlist-objective:
  enabled: true
  value: '%ping%'
  fancy-value: '&7Ping: %ping%'
~~~

FauxPlayers sends the `TAB-PlayerList` score on Paper and Fabric.
It sends the score again after a TAB objective reload.

The TAB `%online%` value includes real players and active fake TAB entries.
It does not change the server's real player count.

## Configuration

The generated file contains all supported settings.
These are the main settings:

~~~yaml
enabled: true

status:
  enabled: true
  include-real-players: true
  include-static-fakes: true
  include-relayed-players: true

count:
  mode: COMBINED       # FIXED, ADDITIONAL, RANDOM, REMOTE, or COMBINED
  fixed: 20

tab:
  enabled: true
  include-static-fakes: true
  include-relayed-players: true
  default-latency: 50
  default-gamemode: SURVIVAL

static-players:
  - name: Herobrine
    latency: 42

relay:
  enabled: false
  source: STATUS        # STATUS or HTTP
  status:
    host: 127.0.0.1
    port: -1             # SRV or port 25565
  http:
    url: http://127.0.0.1:8080/players
  refresh-seconds: 10
~~~

Static entries support `name`, `display-name`, and `latency`.
Static and relayed UUIDs are stable.

## Relays

Relay requests run in the background.
They do not block the server thread.

### STATUS relay

Set `relay.source` to `STATUS`.
Set `relay.status.host` to the upstream server.

FauxPlayers sends a Minecraft STATUS request.
It uses an `_minecraft._tcp` SRV record when available.
Otherwise, it uses the configured port.
Port `-1` uses port 25565 when no SRV record exists.

STATUS samples are not complete rosters.
FauxPlayers does not use them for join or leave messages.

### HTTP relay

Set `relay.source` to `HTTP`.
Set `relay.http.url` to the JSON endpoint.

Recommended response:

~~~json
{
  "online": 3,
  "max": 100,
  "players": [
    {"name": "Alice"},
    {"name": "Bob"},
    {"name": "Charlie"}
  ]
}
~~~

The `players` list is recommended.
A flat `name` field is also accepted.
Use HTTP when the endpoint provides a complete roster.
Roster changes can create fake join and leave messages.

Names classified as `anonymous` or `Anonymous Player` are ignored.
FauxPlayers does not resolve or display them.

If a request fails, `keep-last-successful-result` keeps the last good roster when enabled.

## Join and leave messages

Set `messages.enabled` to `true` to announce HTTP roster changes.

Paper and Fabric observe a real join and leave message.
They reuse its format for relay messages.
This supports custom message formats.

The observed format is stored in `message-format.yml`.
FauxPlayers does not create Bukkit join or quit events for fake players.

## Commands

Use `/fauxplayers`.
`/fp` and `/fakeplayers` are aliases.

| Command | Function |
|---|---|
| `/fauxplayers status` | Shows real and relay state. |
| `/fauxplayers list` | Lists static and cached relay names. |
| `/fauxplayers reload` | Reloads the configuration. |
| `/fauxplayers refresh` | Starts a relay refresh. |
| `/fauxplayers add <name>` | Adds a static entry. |
| `/fauxplayers remove <name>` | Removes a static entry. |
| `/fauxplayers ping <name> <ms>` | Sets a static ping. |
| `/fauxplayers say <name> <message>` | Sends fake chat text. |
| `/fauxplayers get <setting>` | Shows a setting. |
| `/fauxplayers set <setting> <value>` | Changes a setting. |
| `/fauxplayers relay` | Shows relay state. |
| `/fauxplayers relay enable` | Enables the relay. |
| `/fauxplayers relay disable` | Disables the relay. |

Both platforms provide command completion.
Paper requires `fauxplayers.admin`.
Fabric requires the server-operator permission level.

## Troubleshooting

### Fake names do not appear

Check `status.enabled` and `tab.enabled`.
Check the related `include-*` settings.
Check the server log for packet or TAB errors.

### TAB shows one online player

Check that TAB is installed and enabled.
Check that `tab.enabled` is true.
The server's real online count remains unchanged.

### The `Ping: N` score does not appear

Enable TAB's player-list objective.
Install the rebuilt JAR.
Restart the server.

### Relay data is stale

Run `/fauxplayers status`.
Check the cache age and last error.
Run `/fauxplayers refresh`.
Check the host, port, URL, timeout, and relay source.

## License

FauxPlayers is released under the GNU General Public License, version 3.
See [LICENSE](LICENSE).
