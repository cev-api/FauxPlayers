# FauxPlayers

FauxPlayers adds display-only player entries to Minecraft.

It supports:

- Paper 1.21.x -> 26.2
- Fabric for Minecraft 26.2.
- Server status responses.
- Manipulating the in-game player list.
- Relaying players from another server or HTTP source.
- Replaying an exported Discord chat history into the server.
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
  include-replay: true

count:
  mode: COMBINED       # FIXED, ADDITIONAL, RANDOM, REMOTE, or COMBINED
  fixed: 20

tab:
  enabled: true
  include-static-fakes: true
  include-relayed-players: true
  include-replay: true
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

replay:
  enabled: false
  file: replay/chat.csv
  speed: 1.0
  loop: true
  maximum-gap-seconds: 30
  maximum-players: 100
  chat: true
  discord-chat: true
  deaths: true
  events: true
  announce-seeks: true
  skip-real-players: true
~~~

Static entries support `name`, `display-name`, and `latency`.
Static and relayed UUIDs are stable.

## Relays

A relay copies the player list from somewhere else and shows it as fake entries.
The somewhere else is whatever `relay.source` names.

- `relay.source: STATUS` asks the upstream **Minecraft server** directly, so it
  reuses the same public ping in the server list. Point `relay.status.host` at it.
- `relay.source: HTTP` asks **your own URL** for JSON, so you choose the roster.
  Point `relay.http.url` at it.

Both write the same cached roster, and both are shown as fake entries. Choose
`HTTP` when you can serve a complete list; choose `STATUS` when you only have the
other server.

Relay requests run in the background.
They do not block the server thread.

Disabling the relay stops its names being shown. The roster it last fetched is
kept in memory so re-enabling uses it again without a fresh request, but nothing
is displayed from it while the relay is off. `/fauxplayers status` reports
`0 shown | 8 cached` in that state rather than implying the names are in use.

### STATUS relay

Set `relay.source` to `STATUS`.
Set `relay.status.host` to the upstream server.

FauxPlayers sends a Minecraft STATUS request.
It uses an `_minecraft._tcp` SRV record when available.
Otherwise, it uses the configured port.
Port `-1` uses port 25565 when no SRV record exists.

STATUS samples are not complete rosters.
FauxPlayers does not use them for join or leave messages, so a STATUS relay never
produces fake join or leave announcements. Only the HTTP source does.

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

## Real players win

A real player and a fake entry can want the same name, because a replayed log
holds people who are on the server right now.

When that happens the real player keeps everything and the fake one is dropped:

- No second entry is created, so the player list and TAB show one entry, not two.
- No replayed join, leave, or chat is sent under their name, so the replay never
  speaks for someone who is present.

Their own entry and their own words always win. Case does not matter, so a
replayed `notch` is dropped while `Notch` is online.

This covers every source of fake entries: static, relayed, and replayed.

Set `replay.skip-real-players` to `false` to replay the history exactly as it
happened, even where it names someone who is online.

The check runs every few ticks, so a player who logs in while a fake entry of
that name is showing takes over the entry.

## Turning a mode off

Switching a mode off updates the player list straight away and announces the
players leaving, the same way a real player quitting is announced.

The two targets are separate. The **player list** is what real players see in TAB.
The **status sample** is what the server-list shows before anyone connects. A
`tab.*` setting changes only the first, a `status.*` setting changes only the
second, and a mode switch such as `relay.enabled` changes both.

| Toggle | Player list | Status sample |
|---|---|---|
| `enabled: false` | emptied | emptied |
| `tab.enabled: false` | emptied | unchanged |
| `tab.include-static-fakes: false` | static entries removed | unchanged |
| `tab.include-relayed-players: false` | relayed entries removed | unchanged |
| `tab.include-replay: false` | replayed entries removed | unchanged |
| `relay.enabled: false` | relayed entries removed | relayed entries removed |
| `replay.enabled: false` | replayed entries removed | replayed entries removed |
| `status.enabled: false` | unchanged | left entirely alone |
| `status.include-static-fakes: false` | unchanged | static entries removed |
| `status.include-relayed-players: false` | unchanged | relayed entries removed |
| `status.include-replay: false` | unchanged | replayed entries removed |

`status.enabled: false` is the one that leaves the sample untouched rather than
emptying it, because the status response is then the server's own.

The player list is rebuilt every few ticks, so a change lands within a moment of
the command. Turning a mode back on announces the players it adds. A change that
leaves the list alone, such as editing `replay.speed` or adding a text
replacement, costs no messages.

What is announced is the difference the **configuration change itself** makes.
Only the entries that change because of it are announced, so a command that
touches nothing about the player list stays silent even while the relay roster or
the replay is changing underneath it.

Nothing is announced on the first load at server start, because there is no
previous list to compare against. Set `messages.enabled` to `false` to remove
entries without any message.

Disabling the relay keeps its fetched roster in memory so re-enabling uses it
again without a new request, but nothing is displayed from it while it is off.

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
| `/fauxplayers replay` | Shows replay state and the sub-command list. |
| `/fauxplayers replay enable` | Enables the replay. |
| `/fauxplayers replay disable` | Disables the replay. |
| `/fauxplayers replay restart` | Re-reads the file and starts over. |
| `/fauxplayers replay seek <line>` | Jumps to a line. |
| `/fauxplayers replay skip <lines>` | Moves by lines, negative goes back. |
| `/fauxplayers replay replace <from>=<to>` | Adds a text rewrite. |
| `/fauxplayers replay unreplace <from>` | Removes a text rewrite. |
| `/fauxplayers replay replacements` | Lists the text rewrites. |
| `/fauxplayers replay speed <multiplier>` | Sets the timeline speed. |
| `/fauxplayers replay loop <true\|false>` | Sets the loop mode. |
| `/fauxplayers replay file <path>` | Sets the CSV path. |

Both platforms provide command completion.
Paper requires `fauxplayers.admin`.
Fabric requires the server-operator permission level.

### Non-operators cannot discover FauxPlayers

For a non-operator, the FauxPlayers commands do not exist:

- The roots and aliases are absent from the command-tree packet.
- Autocomplete replies never contain them, even when the client sends raw
  suggestion requests such as `/f`, `/fa`, or `/fp`. Paper strips them from the
  suggestion packet with ProtocolLib; Fabric filters them at the command
  dispatcher before any reply is built.
- Executing them reports an unknown command, and completion returns nothing.
- Operators are unaffected.

On Paper, the suggestion-packet filter requires ProtocolLib.
The command-tree removal and the execution guard work without it.

## Replay

Replay reads a Discord chat export and plays it back into the server in the
original order. When the end is reached it starts over unless `replay.loop` is
false.

The file is a Discord channel export with the columns `AuthorID`, `Author`,
`Date`, `Content`, `Attachments`, and `Reactions`. Only `Author`, `Date`, and
`Content` are used.

`replay.file` is relative to the plugin data folder unless it is absolute.

- Paper: `plugins/FauxPlayers/replay/chat.csv`.
- Fabric: `config/fauxplayers/replay/chat.csv`.

### What is replayed

`X joined the game` adds the `X` fake entry and broadcasts the join message.
`X left the game` removes the entry and broadcasts the leave message.
Both are recognised in the `X has joined the server` form as well.

A death message is read from what it says, not from who posted it, because either
account can carry it. `X was slain by Y using [Z]`, `X fell from a high place`,
and `X drowned` replay as death messages whoever the export credits them to.

The victim has to be a name that joined the server, so `X` is checked against
that list. Chat that merely mentions dying, such as `i blew up the front` or
`brodie fell for the ragebait`, stays chat, because the first word is not a
player.

Server shutdown, restart, status, console output, and permission errors are
dropped entirely.

Replayed players are normal faux entries. FauxPlayers resolves the Mojang UUID,
the name casing, and the skin from the name, so their head and icon are real.
They are never server players.

Head lookups keep being retried. A name that failed is asked for again until it
resolves, and a head is rebuilt as soon as the real skin arrives.

### The two chat sources

The channel holds both in-game chat and Discord chat, and the export separates
them by which account posted the line, not by the name in the text.

Two accounts exist:

- The **server bot** posts joins, leaves, deaths, server output, and in-game chat
  written as `Player: message`.
- The **relay account** posts in-game chat under the **player's own name**. Over
  time these servers switched from the first style to the second, so an export
  usually contains both.

A Discord user posts under their own account, under their own Discord handle.
That handle is often a lowercase version of a player's in-game name, which is
why the name alone cannot decide it.

FauxPlayers reads the account from the `AuthorID` column:

- The bot is the account that posts the join lines under a name that is not a
  player. The relay posts join lines too, but it posts them under the joining
  player's own name, so those are not mistaken for the bot.
- The relay is an account that posts under many different names, and those names
  belong to people who joined the server. A Discord account only ever posts under
  one name, so it never qualifies.
- A relayed line counts as in-game chat only when its name is one that joined the
  server. A label that never joined stays server output, as with the name `Server`
  for the server's own announcements.

Anything left is Discord chat.

### Unattributed messages

The server bot is the server's own account, so it never chats on its own behalf.
It only ever carries a join, a leave, a death, server output, or in-game chat
written as `Player: message`.

A bot line that has no player on it is therefore an unattributed relay, where the
name was lost on the way through. FauxPlayers ignores those instead of showing
them, which is what keeps the server from appearing to say things like
`nothing` on its own.

An in-game chat line is recognised by its `Player: message` shape, and the player
does not have to be one the export recorded joining. So `FieryGrowth: wsp`
replays as chat from `FieryGrowth` even in a file that never shows them logging
in. A name written with a leading dot, as some servers do, is trimmed.

In-game chat shows as `<Player> message` and Discord chat as
`[Discord] <user> message`.

Discord users are not in the game, so they never become player entries and they
never get a player head. Only the join and leave lines create replayed players,
and those name in-game accounts.

Set `replay.discord-chat` to `false` to leave the Discord conversation out and
replay only what happened on the server. Set `replay.chat` to `false` to drop
the in-game chat instead.

Run `/fauxplayers replay` to see which accounts were detected.

### Seeking

`/fauxplayers replay` shows the position as `Lines: 2/547884`, where the figure
counts the lines already passed. Seeking uses the same numbering:

- `/fauxplayers replay seek 30000` jumps to that line.
- `/fauxplayers replay skip 5000` moves forward five thousand lines.
- `/fauxplayers replay skip -5000` moves back five thousand lines.

Seeking rebuilds the player list from the joins and leaves before that line
instead of replaying them, so the roster, the player count, and the TAB list are
correct for that moment straight away.

The jump is then announced as a normal roster change: players who were in the
game at the old line and are not at the new one leave, and players who are only
at the new line join. A leap therefore reads the same way as any other change to
the player list, rather than the list flickering with no explanation.

A player whose join line sits before the seek point is already listed and does
not join again, and their next leave line still removes them. Chat is never out
of step with the player list: everyone who speaks after a seek is someone the
roster already holds.

Set `replay.announce-seeks` to `false` for a silent jump, which keeps the roster
correct without the messages.

### Text replacement

Lines can be rewritten as they are replayed, which is useful for scrubbing a
phrase, a slur, a link, or a name before anyone sees it. The same feature is
sometimes called filtering.

Run `/fauxplayers replay` to see the full list of sub-commands, which is built
from the same list that drives completion:

~~~text
Use /fauxplayers replay <enable|disable|file|speed|loop|restart|seek|skip|chat
|discord-chat|deaths|events|maximum-gap-seconds|maximum-players|replace|unreplace
|replacements> ...
~~~

~~~yaml
replay:
  replacements:
    - from: "annoying phrase"
      to: "replacement"
    - from: "discord.gg/abcdef"
      to: ""
~~~

Each `from` is matched as literal text, case sensitively, and every occurrence
is replaced. An empty `to` deletes the match.

The rules are applied to the whole line before it is replayed, to in-game chat,
Discord chat, death messages, and other server output alike. Matching happens
after the line is classified, so a rewrite can never turn a join into a chat
message or hide a death.

In game:

- `/fauxplayers replay replace <from>=<to>` adds one. The first `=` splits it, so
  spaces work on both sides.
- `/fauxplayers replay unreplace <from>` removes one.
- `/fauxplayers replay replacements` lists them.

Run `/fauxplayers replay restart` after a change so the loaded lines are rebuilt.

### Reading the status

`/fauxplayers replay` reports enough to tell a waiting replay from a stalled one:

~~~text
Replay » enabled=true | file=legacy.csv | speed=1.0 | loop=true
Lines: 28/547884 | players: 3 | finished: false
Clock: 2h 14m of 373d 4h 0m | next line in 12s | ticks driven: 8420
~~~

- **Lines** counts the lines already replayed, out of the lines in the file. It
  starts at 0 and the first line is dispatched on the first tick after enabling.
- **Clock** is how far into the original timeline the replay has reached, out of
  the whole timeline.
- **next line in** is the wait until the next line is due. A large figure is the
  replay playing a quiet stretch, not a problem. Use `skip` to pass it.
- **ticks driven** counts how often the game loop has driven the replay. It must
  keep climbing. A figure that never changes means the loop is not running, which
  is worth reporting, because a replay that is waiting still counts ticks.

### Speed and gaps

The replay is real-time by default. `replay.speed` compresses the clock: `10`
replays ten seconds of history per real second.

A long quiet period would stall the replay, so `replay.maximum-gap-seconds` caps
the wait before any one line. The default of `30` turns a two day outage into a
thirty second pause.

The capped waits add up, so the cap only costs one pause per quiet period. Lines
that were a second apart still replay a second apart, even straight after a
capped outage. A low value therefore compresses every wait, and a high value
keeps long silences intact. This is also the knob for quiet and empty stretches:
the wait before a line while nobody is online is capped the same way.

Speed and the cap decide the run time together. As a reference, the sample
export covers 373 days and replays in about 73 days at `1.0`, 17 hours at `100`,
and under 2 hours at `1000`.

The replay emits at most 200 lines per tick, about 4000 per second, so a very
high speed settles at that rate instead of going faster.

### Ordering caveats

- `replay.chat` replays in-game chat, `replay.discord-chat` replays Discord chat,
  `replay.deaths` replays death messages, and `replay.events` replays the rest of
  the server output. Turn one off to keep a quieter channel.
- `replay.maximum-players` limits how many replayed players can be listed at once.
  Players above the limit still chat, but do not get a player-list entry.
- The export is parsed once on startup. Change `replay.chat`,
  `replay.discord-chat`, `replay.deaths`, `replay.events`, or
  `replay.maximum-gap-seconds` and then run `/fauxplayers replay restart`.
- A line the relay carries under the label `Server`, meaning the server speaking
  about itself, still replays without a speaker when `replay.events` is on. Set
  `replay.events` to `false` to drop those too.
- Some servers forward their own chat to Discord and then take it back again. The
  echo is a second line with the same words, so it replays twice. The same
  happens to a death that both accounts carry.
- A replay covering people who are on the server now leaves their lines out. See
  the real players section above.

A Discord handle and the in-game name can differ. Replay keeps each line under
the name the export used for it.

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

### The replay is enabled but nothing happens

Run `/fauxplayers replay` and read the figures.

- `Loaded: (none)` means the file has not been read yet, or the path is wrong.
  A large export takes a few seconds to parse before the first line replays.
- `next line in` counting down to a large figure means the replay is playing a
  quiet stretch of the history. That is the file being followed faithfully. Use
  `/fauxplayers replay skip <lines>` to pass it, or lower
  `replay.maximum-gap-seconds`.
- `ticks driven` frozen means the game loop is not driving the replay. The server
  log will hold a warning naming the cause, because a failure in the loop is
  reported instead of silently stopping it.

### Replay does not start

Run `/fauxplayers replay`.
Check `replay.enabled` and the resolved `file` path.
Check the last error line and the server log.
Run `/fauxplayers replay restart` after changing the file or the text
replacements, which are also called filters.

The first load parses the whole file in the background.
A large export takes a few seconds before the first line is replayed.

### A replayed player has the default head

A head is only real when the name resolves to a Mojang account. FauxPlayers keeps
asking: a name that failed is retried every twenty seconds, and a head already sent
is rebuilt as soon as the real skin arrives, so nothing stays wrong for the rest of
the session.

Mojang lookups run over the plugin's own HTTP client with a generous timeout.
A slow reply is never treated as "no such account"; only an answer of 404 or 204
is, and even then the name is retried, just less often.

Three things can stop a head from being real.

- The account was **renamed or deleted** since the export was written. Mojang only
  resolves current names, so a name from an old log can answer 404 with no way to
  recover the skin. Those are still retried, but only every ten minutes, because
  asking more often cannot help. This is permanent for that name.
- The lookups were **throttled**. Mojang answers a burst of name lookups with HTTP
  429. FauxPlayers spaces its lookups out and never remembers a failure, so a
  throttled name is retried and the head fills in shortly afterwards.
- Mojang was **slow or unreachable**. The lookup is retried and the head is
  rebuilt when it succeeds.

A warning in the log names the reason the first time it happens.

## License

FauxPlayers is released under the GNU General Public License, version 3.
See [LICENSE](LICENSE).
