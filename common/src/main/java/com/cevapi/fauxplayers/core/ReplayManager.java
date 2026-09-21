package com.cevapi.fauxplayers.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replays a Discord chat-export CSV into the server, chronologically and in a loop.
 * The timeline is parsed on a worker thread; only the cursor moves on the game thread.
 */
public final class ReplayManager {
    public enum Kind { JOIN, LEAVE, CHAT, DISCORD, DEATH, EVENT }

    /** One replayed line. {@code text} is null for roster events. */
    public record Event(long atMillis, Kind kind, String actor, String text) { }

    /** A parsed export line. A null kind means chat or server output, decided later. */
    private record Raw(long atMillis, Kind kind, boolean death, String id, String actor, String text) { }

    public interface Host {
        void fine(String message);

        void warning(String message);

        /** Runs on the game thread, in timeline order. */
        void onReplayEvent(Event event);
    }

    private static final int MAX_EVENTS_PER_TICK = 200;
    /** Posts needed before an account is accepted as the server's bridge bot. */
    private static final int BRIDGE_MINIMUM_POSTS = 2;
    /** How far the busiest server-message account must lead the runner-up. */
    private static final int BRIDGE_DOMINANCE = 4;
    /** Server-message posts needed for the fallback signal to be trusted. */
    private static final int BRIDGE_FALLBACK_MINIMUM = 10;
    /**
     * Distinct author names one export account must post under before it is read as the relay
     * that speaks for in-game players. A Discord account only posts under its own name.
     */
    private static final int RELAY_MINIMUM_NAMES = 3;

    private static final Pattern JOIN = Pattern.compile(
            "^(\\S{1,32}?)\\s+(?:has\\s+)?joined the (?:game|server)[.!]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEAVE = Pattern.compile(
            "^(\\S{1,32}?)\\s+(?:has\\s+)?left the (?:game|server)[.!]?$", Pattern.CASE_INSENSITIVE);
    /**
     * The bridge relays in-game chat to Discord as {@code Name: message}. That is in-game chat,
     * and the name in front of the colon is the player who spoke.
     */
    private static final Pattern SPEAKER = Pattern.compile("^\\.?([A-Za-z0-9_]{3,16}):\\s+(\\S.*)$");
    /** A rank tag some servers put in front of the name, such as {@code [ADMIN]}. */
    private static final Pattern RANK_PREFIX = Pattern.compile("^\\[[^\\]]{0,24}\\]\\s*");
    /**
     * Death messages. Only the bridge bot's lines are tested against this, so it can stay
     * permissive: a wrong answer only moves a line between the two server-output groups.
     */
    private static final Pattern DEATH = Pattern.compile(
            "^(?:\\S{1,32}\\s+)?(?:was|went|died|drowned|burned|burnt|blew|starved|suffocated|froze"
                    + "|fell|walked|hit|experienced|tried|withered|discovered|stepped|didn't|did"
                    + "|left the confines)\\b",
            Pattern.CASE_INSENSITIVE);
    /**
     * A death message is {@code <victim> <what happened>} and nothing else. Matching the whole
     * shape is what separates it from chat that merely mentions dying, so the verbs are listed
     * rather than accepting any word after "was".
     */
    private static final Pattern DEATH_MESSAGE = Pattern.compile(
            "^(\\S{3,16})\\s+(?:was\\s+(?:slain|shot|blown|killed|smashed|impaled|speared|fireballed"
                    + "|pummeled|pummelled|stung|skewered|squashed|squished|pricked|poked|struck|sniped"
                    + "|obliterated|roasted|dragged|withered|bitten|electrocuted|buffeted|defenestrated"
                    + "|flattened|burned|burnt|drowned|swatted|sliced|clapped|crushed|doomed|frozen"
                    + "|spiked|yeeted|knocked|butchered)\\b"
                    + "|died\\b|fell\\b|drowned\\b|blew up\\b|went off with a bang\\b"
                    + "|starved to death\\b|suffocated in a wall\\b|froze to death\\b"
                    + "|walked into (?:a cactus|fire|the danger zone)\\b|hit the ground too hard\\b"
                    + "|tried to swim in lava\\b|discovered the floor was lava\\b"
                    + "|experienced kinetic energy\\b|left the confines of this world\\b"
                    + "|withered away\\b|burn(?:ed|t) (?:to death|to a crisp)\\b"
                    + "|went up in flames\\b|didn't want to live\\b|is doomed to fall\\b)");
    private static final Pattern SERVER_NOISE = Pattern.compile(
            "^server is (starting|stopping)|^server has (started|stopped)|executed command"
                    + "|\\bserver (restart|shutdown|reboot|stopping|starting|stopped|started|update|offline|online)\\b"
                    + "|\\brestart\\w*|^daily server restart|^server (mspt|tps|used memory|status|version)"
                    + "|^online players \\(|^no players online|^={3,}|\\bserver mspt\\b|\\bserver tps\\b"
                    + "|^you do not have permission|^you need to wait for the server|^TranslateError\\{",
            Pattern.CASE_INSENSITIVE);
    /** Emoji, symbol and formatting runs that the export puts in front of events. */
    private static final Pattern LEADING_NOISE =
            Pattern.compile("^[\\s\\p{So}\\p{Sk}\\p{Mn}\\p{Me}\\p{Cf}\\p{Co}\\p{Cs}]+");
    private static final Pattern MARKDOWN_ESCAPE = Pattern.compile("\\\\([_*~`|>#\\-+.!\\[\\]()])");

    private final Host host;
    private volatile PluginConfig config;
    private volatile Path file;
    private volatile Path loadedFile;
    private volatile List<Event> timeline;
    private volatile List<String[]> replacements = List.of();
    private volatile List<String> bridgeAuthors = List.of();
    private volatile List<String> relayAuthors = List.of();
    private volatile Set<String> knownPlayers = Set.of();
    private volatile String lastError;
    private volatile boolean finished;
    private int cursor;
    private long virtualMillis;
    private long lastTickMillis;
    /** How often the game loop has driven this replay, which shows whether it is being ticked. */
    private long ticks;
    private final LinkedHashMap<String, String> roster = new LinkedHashMap<>();
    private List<FauxPlayerEntry> rosterEntries = List.of();
    private Thread loader;

    public ReplayManager(Host host) {
        this.host = host;
    }

    public synchronized void start(PluginConfig config, Path file) {
        start(config, file, List.of());
    }

    /**
     * @param knownPlayers in-game names the server already knows, so that a slice of an export
     *                     that does not record their join still reads their chat as in-game chat.
     */
    public synchronized void start(PluginConfig config, Path file, Collection<String> knownPlayers) {
        this.config = config;
        this.file = file;
        this.knownPlayers = knownPlayers == null ? Set.of() : new LinkedHashSet<>(knownPlayers);
        this.replacements = config.replayReplacements;
        if (!config.replayEnabled || file == null) {
            clear();
            return;
        }
        if (timeline != null && file.equals(loadedFile)) return;
        load(config, file);
    }

    public synchronized void stop() {
        clear();
        timeline = null;
        loadedFile = null;
    }

    /** Re-reads the file and restarts the timeline from the first line. */
    public synchronized void restart() {
        if (config != null && config.replayEnabled && file != null) {
            load(config, file);
            return;
        }
        clear();
    }

    /**
     * Jumps to an absolute line. The roster is rebuilt from the joins and leaves before that line
     * rather than by replaying them, so the player list is correct without broadcasting history.
     *
     * @param line zero based, matching the {@code Lines: X/Y} figure the status command shows.
     * @return false when no timeline is loaded yet.
     */
    public synchronized boolean seek(int line) {
        List<Event> events = timeline;
        if (events == null || events.isEmpty()) return false;
        int target = Math.max(0, Math.min(line, events.size()));
        PluginConfig current = config;
        boolean announce = current != null && current.replayAnnounceSeeks;
        // Who was in the game at the old line, so the jump can be reported as people leaving and
        // joining. Without that the player list would simply flicker with no explanation.
        Map<String, String> before = new LinkedHashMap<>(roster);
        roster.clear();
        int maximum = current == null ? 0 : current.replayMaximumPlayers;
        for (int index = 0; index < target; index++) {
            Event event = events.get(index);
            if (event.kind() == Kind.JOIN) {
                String id = key(event.actor());
                if (roster.containsKey(id)) roster.put(id, event.actor());
                else if (maximum <= 0 || roster.size() < maximum) roster.put(id, event.actor());
            } else if (event.kind() == Kind.LEAVE) {
                roster.remove(key(event.actor()));
            }
        }
        rosterEntries = buildEntries();
        cursor = target;
        virtualMillis = target == 0 ? 0L : events.get(target - 1).atMillis();
        // Zeroing this makes the next tick resynchronise instead of charging the time the seek took.
        lastTickMillis = 0L;
        finished = false;
        if (announce) {
            for (Map.Entry<String, String> entry : roster.entrySet()) {
                if (!before.containsKey(entry.getKey())) {
                    host.onReplayEvent(new Event(virtualMillis, Kind.JOIN, entry.getValue(), null));
                }
            }
            for (Map.Entry<String, String> entry : before.entrySet()) {
                if (!roster.containsKey(entry.getKey())) {
                    host.onReplayEvent(new Event(virtualMillis, Kind.LEAVE, entry.getValue(), null));
                }
            }
        }
        return true;
    }

    /**
     * Moves by a number of lines, negative to go back.
     *
     * @return false when no timeline is loaded yet.
     */
    public synchronized boolean skip(int lines) {
        return seek(cursor + lines);
    }
    /** Advances the replay clock. Called once per server tick from the game thread. */
    public void tick() {
        ticks++;
        PluginConfig current = config;
        List<Event> events = timeline;
        if (current == null || !current.replayEnabled || events == null || events.isEmpty()) return;
        long now = System.currentTimeMillis();
        // The first tick only starts the clock, and it still evaluates below so anything already due
        // is dispatched at once. Returning early here made a freshly enabled replay read as
        // Lines: 0 for a tick, which looks like a replay that is not running.
        if (lastTickMillis != 0L) {
            virtualMillis += (long) ((now - lastTickMillis) * current.replaySpeed);
        }
        lastTickMillis = now;
        if (finished) {
            if (current.replayLoop) clear();
            return;
        }
        int dispatched = 0;
        while (cursor < events.size() && events.get(cursor).atMillis() <= virtualMillis
                && dispatched < MAX_EVENTS_PER_TICK) {
            apply(events.get(cursor++));
            dispatched++;
        }
        if (cursor >= events.size()) {
            finished = true;
            if (current.replayLoop) {
                clear();
            } else {
                host.fine("Replay finished after " + events.size() + " lines.");
            }
        }
    }

    /** Snapshot of the players the replay currently considers online. */
    public synchronized List<FauxPlayerEntry> entries() {
        return rosterEntries;
    }

    public String loadedFile() {
        return loadedFile == null ? null : loadedFile.toString();
    }

    public String lastError() {
        return lastError;
    }

    /** The accounts the parser found posting the server's join and leave lines. */
    public List<String> bridgeAuthors() {
        return bridgeAuthors;
    }

    /** The accounts the parser found relaying in-game chat under each player's own name. */
    public List<String> relayAuthors() {
        return relayAuthors;
    }

    public int eventCount() {
        List<Event> events = timeline;
        return events == null ? 0 : events.size();
    }

    public synchronized int position() {
        return cursor;
    }

    /** How often the game loop has driven the replay since it was started. */
    public long ticks() {
        return ticks;
    }

    /** How far into the original timeline the replay has reached. */
    public synchronized long clockMillis() {
        return virtualMillis;
    }

    /** Length of the whole timeline. */
    public synchronized long timelineMillis() {
        List<Event> events = timeline;
        return events == null || events.isEmpty() ? 0L : events.get(events.size() - 1).atMillis();
    }

    /**
     * Milliseconds until the next line is due, or -1 when there is none. A replay that looks stuck
     * because it is waiting shows this as a countdown rather than as no progress.
     */
    public synchronized long nextDueInMillis() {
        List<Event> events = timeline;
        if (events == null || cursor >= events.size()) return -1L;
        return Math.max(0L, events.get(cursor).atMillis() - virtualMillis);
    }

    public boolean finished() {
        return finished;
    }

    public synchronized int playerCount() {
        return roster.size();
    }

    public synchronized List<String> playerNames() {
        return List.copyOf(roster.values());
    }

    private synchronized void clear() {
        roster.clear();
        rosterEntries = List.of();
        cursor = 0;
        virtualMillis = 0;
        lastTickMillis = 0;
        finished = false;
    }

    private void load(PluginConfig current, Path source) {
        if (loader != null && loader.isAlive()) loader.interrupt();
        loader = new Thread(() -> {
            try {
                List<Event> parsed = parse(current, source);
                publish(parsed, source);
            } catch (Exception error) {
                lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
                host.warning("Unable to read the replay file " + source + ": " + lastError);
            }
        }, "FauxPlayers-replay");
        loader.setDaemon(true);
        loader.start();
    }

    private synchronized void publish(List<Event> parsed, Path source) {
        timeline = parsed;
        loadedFile = source;
        lastError = null;
        clear();
        host.fine("Replay loaded " + parsed.size() + " of the lines in " + source.getFileName()
                + "; server messages are posted by "
                + (bridgeAuthors.isEmpty() ? "no detected account" : String.join(", ", bridgeAuthors)) + ".");
    }

    private void apply(Event event) {
        switch (event.kind()) {
            case JOIN -> addPlayer(event.actor());
            case LEAVE -> removePlayer(event.actor());
            default -> { }
        }
        host.onReplayEvent(event);
    }

    private synchronized void addPlayer(String name) {
        PluginConfig current = config;
        String key = name.toLowerCase(Locale.ROOT);
        if (roster.containsKey(key)) {
            roster.put(key, name);
            rosterEntries = buildEntries();
            return;
        }
        if (current != null && current.replayMaximumPlayers > 0
                && roster.size() >= current.replayMaximumPlayers) {
            return;
        }
        roster.put(key, name);
        rosterEntries = buildEntries();
    }

    private synchronized void removePlayer(String name) {
        if (roster.remove(name.toLowerCase(Locale.ROOT)) != null) rosterEntries = buildEntries();
    }

    private List<FauxPlayerEntry> buildEntries() {
        PluginConfig current = config;
        int latency = current == null ? 50 : current.defaultLatency;
        String mode = current == null ? "SURVIVAL" : current.defaultMode;
        List<FauxPlayerEntry> built = new ArrayList<>(roster.size());
        for (String name : roster.values()) {
            built.add(new FauxPlayerEntry(name, FauxPlayerEntry.stable("replay", name), name,
                    latency, mode, true));
        }
        return List.copyOf(built);
    }

    private List<Event> parse(PluginConfig current, Path source) throws IOException {
        List<Raw> parsed = new ArrayList<>();
        Map<String, Integer> joinPosts = new HashMap<>();
        Map<String, Integer> serverPosts = new HashMap<>();
        Map<String, Set<String>> idToNames = new HashMap<>();
        Map<String, String> interned = new HashMap<>();
        Set<String> joined = new LinkedHashSet<>();
        Clock clock = new Clock(Math.max(0, current.replayMaximumGapSeconds) * 1000L);
        try (CsvRecords records = new CsvRecords(source)) {
            String[] header = records.next();
            if (header == null) return List.of();
            int idIndex = column(header, "AuthorID", 0);
            int authorIndex = column(header, "Author", 1);
            int dateIndex = column(header, "Date", 2);
            int contentIndex = column(header, "Content", 3);
            int required = Math.max(contentIndex, Math.max(authorIndex, Math.max(idIndex, dateIndex))) + 1;
            String[] row;
            while ((row = records.next()) != null) {
                if (row.length < required) continue;
                String content = row[contentIndex];
                if (content == null || content.isBlank()) continue;
                long timestamp = timestamp(row[dateIndex]);
                if (timestamp == Long.MIN_VALUE) continue;
                String text = unwrap(content);
                if (text == null || text.isEmpty()) continue;
                // The export reports the account, which is what separates the server's own relay
                // from a Discord user. Many relayed messages share one account and vary the name.
                String authorId = interned.computeIfAbsent(
                        row[idIndex] == null ? "" : row[idIndex].strip(), value -> value);
                String author = cleanAuthor(row[authorIndex]);
                idToNames.computeIfAbsent(authorId, ignored -> new LinkedHashSet<>()).add(author);
                Matcher join = JOIN.matcher(text);
                Matcher leave = join.matches() ? null : LEAVE.matcher(text);
                if (join.matches() || (leave != null && leave.matches())) {
                    boolean arrived = join.matches();
                    String name = playerName(arrived ? join.group(1) : leave.group(1));
                    if (name == null) continue;
                    // The account behind the server's own messages is picked from the data,
                    // never from the configuration. Joins and leaves are its clearest trace.
                    joinPosts.merge(author, 1, Integer::sum);
                    joined.add(key(name));
                    parsed.add(new Raw(clock.place(timestamp), arrived ? Kind.JOIN : Kind.LEAVE,
                            false, authorId, name, null));
                    continue;
                }
                if (SERVER_NOISE.matcher(text).find()) {
                    serverPosts.merge(author, 1, Integer::sum);
                    continue;
                }
                boolean death = DEATH.matcher(text).find();
                if (death) serverPosts.merge(author, 1, Integer::sum);
                // Rewrite only now, so the join, leave and death checks always see the real text.
                parsed.add(new Raw(clock.place(timestamp), null, death, authorId, author, rewrite(text)));
            }
        }
        Map<String, String> bot = detectBridges(joinPosts, serverPosts, joined);
        Set<String> relays = detectRelayIds(idToNames, joined);
        Set<String> players = new LinkedHashSet<>(joined);
        // Names the server already knows are players, whether or not this file records their join.
        for (String name : knownPlayers) {
            String player = playerName(name);
            if (player != null) players.add(key(player));
        }
        relayAuthors = List.copyOf(relays);
        return resolve(current, parsed, bot, relays, players);
    }

    /**
     * Turns the parsed lines into the final timeline. The accounts and the player names are only
     * known once the whole file is read, so the split happens here.
     */
    private List<Event> resolve(PluginConfig current, List<Raw> parsed, Map<String, String> bot,
                                Set<String> relays, Set<String> players) {
        bridgeAuthors = List.copyOf(bot.values());
        List<Event> events = new ArrayList<>(parsed.size());
        for (Raw line : parsed) {
            if (line.kind() != null) {
                events.add(new Event(line.atMillis(), line.kind(), line.actor(), null));
                continue;
            }
            // A death is a server message whoever carried it, so it is recognised by what it says
            // rather than by the account that posted it. The victim is the first word and has to be
            // a name that joined the server, which keeps chat such as "i blew up the front" out.
            if (deathMessage(line.text(), players) != null) {
                if (current.replayDeaths) {
                    events.add(new Event(line.atMillis(), Kind.DEATH, line.actor(), line.text()));
                }
                continue;
            }
            if (bot.containsKey(key(line.actor()))) {
                Matcher speaker = SPEAKER.matcher(line.text());
                if (speaker.matches()) {
                    // The bot mirrored in-game chat out as "Player: message". The shape is what
                    // identifies the speaker; the name does not have to be one already known.
                    if (current.replayChat) {
                        events.add(new Event(line.atMillis(), Kind.CHAT, speaker.group(1), speaker.group(2)));
                    }
                    continue;
                }
                // The bot is the server's own account, so it never chats on its own behalf. A line
                // that is not a join, leave, death or recognised server output has no attributed
                // user, which means the relay lost the name on the way through. Ignore it rather
                // than showing it as though the server itself spoke.
                if (isServerOutput(line.text())) {
                    if (current.replayEvents) {
                        events.add(new Event(line.atMillis(), Kind.EVENT, line.actor(), line.text()));
                    }
                }
                continue;
            }
            if (relays.contains(line.id())) {
                // The relay posts under the in-game player's own name. A name that never joined is
                // a label rather than a player, such as Server for its own announcements.
                String player = playerName(line.actor());
                if (player != null && players.contains(key(player))) {
                    if (current.replayChat) {
                        events.add(new Event(line.atMillis(), Kind.CHAT, player, line.text()));
                    }
                    continue;
                }
                if (current.replayEvents) {
                    events.add(new Event(line.atMillis(), Kind.EVENT, line.actor(), line.text()));
                }
                continue;
            }
            // Everything else is a Discord user typing. Those messages replay as text only: a
            // Discord user is not in the game, so they never become a player entry and never get
            // a player head. Only the join and leave lines create replayed players.
            if (current.replayDiscordChat) {
                events.add(new Event(line.atMillis(), Kind.DISCORD, line.actor(), line.text()));
            }
        }
        return events;
    }

    /** Returns the victim name when the line is a death message naming a player, otherwise null. */
    private static String deathMessage(String text, Set<String> players) {
        Matcher matcher = DEATH_MESSAGE.matcher(text);
        if (!matcher.find()) return null;
        String victim = playerName(matcher.group(1));
        return victim != null && players.contains(key(victim)) ? victim : null;
    }

    /**
     * True when a line is recognisable server output rather than someone talking. Anything else on
     * the server's own account is an unattributed relay of somebody else's words.
     */
    private static boolean isServerOutput(String text) {
        return text.endsWith("joined the game") || text.endsWith("left the game")
                || text.endsWith("joined the server!") || text.endsWith("left the server!")
                || text.startsWith("Server ") || text.startsWith("Server is ")
                || text.startsWith("Server has ") || text.startsWith("<>") || text.startsWith("[")
                || text.startsWith("You ") || text.contains(" executed command");
    }

    /** Applies the configured literal rewrites to one line of text. */
    private String rewrite(String text) {
        if (text == null || text.isEmpty()) return text;
        List<String[]> rules = replacements;
        if (rules.isEmpty()) return text;
        String result = text;
        for (String[] rule : rules) {
            if (rule.length < 2 || rule[0].isEmpty()) continue;
            result = result.replace(rule[0], rule[1]);
        }
        return result;
    }

    /** The rewrites currently in force, as {from, to} pairs. */
    public List<String[]> replacements() {
        return replacements;
    }

    /**
     * Finds the export accounts that speak for in-game players by posting under the player's own
     * name. Such an account posts under many different names, and those names belong to people who
     * joined the server. A Discord account only posts under its own name, so it never qualifies.
     */
    private static Set<String> detectRelayIds(Map<String, Set<String>> idToNames, Set<String> joined) {
        Set<String> relays = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : idToNames.entrySet()) {
            Set<String> names = entry.getValue();
            if (names.size() < RELAY_MINIMUM_NAMES) continue;
            int accounted = 0;
            for (String name : names) {
                String player = playerName(name);
                if (player != null && joined.contains(key(player))) accounted++;
            }
            if (accounted * 2 >= names.size()) relays.add(entry.getKey());
        }
        return relays;
    }

    /**
     * Enumerates the bridge bot from the export itself. The join and leave lines state it
     * outright. When an export slice has none of those, the account that dominates the
     * server output is used instead, but only if it clearly leads every other account.
     */
    private static Map<String, String> detectBridges(Map<String, Integer> joinPosts,
                                                     Map<String, Integer> serverPosts,
                                                     Set<String> joined) {
        Map<String, String> bridged = new LinkedHashMap<>();
        // The relay posts join lines too, but under the joining player's own name. The server bot
        // posts under a handle that is not a player, so that is what tells the two apart.
        String chosen = null;
        int chosenCount = 0;
        for (Map.Entry<String, Integer> entry : joinPosts.entrySet()) {
            if (entry.getValue() < BRIDGE_MINIMUM_POSTS) continue;
            String name = playerName(entry.getKey());
            if (name != null && joined.contains(key(name))) continue;
            if (entry.getValue() > chosenCount) {
                chosen = entry.getKey();
                chosenCount = entry.getValue();
            }
        }
        if (chosen != null) {
            bridged.put(key(chosen), chosen);
            return bridged;
        }
        String busiest = null;
        int busiestCount = 0;
        int runnerUp = 0;
        for (Map.Entry<String, Integer> entry : serverPosts.entrySet()) {
            if (entry.getValue() > busiestCount) {
                runnerUp = busiestCount;
                busiest = entry.getKey();
                busiestCount = entry.getValue();
            } else if (entry.getValue() > runnerUp) {
                runnerUp = entry.getValue();
            }
        }
        if (busiest != null && busiestCount >= BRIDGE_FALLBACK_MINIMUM
                && busiestCount >= runnerUp * BRIDGE_DOMINANCE) {
            bridged.put(key(busiest), busiest);
        }
        return bridged;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Maps each accepted line onto the replay clock. The cap applies to the wait before each
     * line and the capped waits accumulate, so a long quiet period costs at most one cap and
     * the lines that follow it still play at their true spacing.
     */
    private static final class Clock {
        private final long gapCapMillis;
        private long lastReal = Long.MIN_VALUE;
        private long at;

        Clock(long gapCapMillis) {
            this.gapCapMillis = gapCapMillis;
        }

        long place(long timestamp) {
            if (lastReal == Long.MIN_VALUE) {
                lastReal = timestamp;
                return 0;
            }
            long gap = Math.max(0, timestamp - lastReal);
            if (timestamp > lastReal) lastReal = timestamp;
            at += gapCapMillis > 0 ? Math.min(gap, gapCapMillis) : gap;
            return at;
        }
    }

    /** Removes markdown framing and leading emoji from one exported message. */
    private static String unwrap(String raw) {
        String text = raw.strip();
        if (text.isEmpty() || text.contains("```") || text.startsWith("===============")) return null;
        if (text.length() >= 4 && text.startsWith("**") && text.endsWith("**")) {
            text = text.substring(2, text.length() - 2);
        } else if (text.length() >= 2 && text.startsWith("`") && text.endsWith("`")) {
            text = text.substring(1, text.length() - 1);
        } else if (text.length() >= 2 && text.startsWith("*") && text.endsWith("*")) {
            text = text.substring(1, text.length() - 1);
        }
        text = MARKDOWN_ESCAPE.matcher(text).replaceAll("$1");
        text = LEADING_NOISE.matcher(text).replaceFirst("");
        return text.strip();
    }

    private static String playerName(String value) {
        String name = RANK_PREFIX.matcher(value.strip()).replaceFirst("").strip();
        int start = 0;
        int end = name.length();
        while (start < end && !isNameCharacter(name.charAt(start))) start++;
        while (end > start && !isNameCharacter(name.charAt(end - 1))) end--;
        return end - start < 3 ? null : name.substring(start, end);
    }

    private static boolean isNameCharacter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9') || value == '_';
    }

    private static String cleanAuthor(String author) {
        if (author == null) return "Server";
        String value = author.strip();
        int hash = value.lastIndexOf('#');
        if (hash > 0 && value.length() - hash == 5 && value.substring(hash + 1).chars().allMatch(Character::isDigit)) {
            value = value.substring(0, hash);
        }
        return value.isEmpty() ? "Server" : value;
    }

    private static int column(String[] header, String name, int fallback) {
        for (int index = 0; index < header.length; index++) {
            if (header[index] != null && header[index].strip().equalsIgnoreCase(name)) return index;
        }
        return fallback;
    }

    private static long timestamp(String value) {
        if (value == null || value.isBlank()) return Long.MIN_VALUE;
        try {
            return OffsetDateTime.parse(value.strip()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return Long.MIN_VALUE;
        }
    }

    /** Minimal RFC 4180 reader: quoted fields, embedded commas, embedded newlines. */
    private static final class CsvRecords implements Closeable {
        private final Reader source;
        private final char[] buffer = new char[1 << 16];
        private int size;
        private int position;
        private final List<String> fields = new ArrayList<>();
        private final StringBuilder field = new StringBuilder();

        CsvRecords(Path path) throws IOException {
            source = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        }

        private int read() throws IOException {
            if (position >= size) {
                size = source.read(buffer);
                position = 0;
                if (size <= 0) return -1;
            }
            return buffer[position++];
        }

        String[] next() throws IOException {
            fields.clear();
            field.setLength(0);
            boolean any = false;
            int state = 0;
            while (true) {
                int value = read();
                if (value < 0) {
                    if (!any) return null;
                    fields.add(field.toString());
                    return fields.toArray(new String[0]);
                }
                any = true;
                char current = (char) value;
                switch (state) {
                    case 0 -> {
                        if (current == '"') state = 2;
                        else if (current == ',') fields.add("");
                        else if (current == '\n') {
                            fields.add("");
                            return fields.toArray(new String[0]);
                        } else if (current != '\r') {
                            field.append(current);
                            state = 1;
                        }
                    }
                    case 1 -> {
                        if (current == ',') {
                            fields.add(field.toString());
                            field.setLength(0);
                            state = 0;
                        } else if (current == '\n') {
                            fields.add(field.toString());
                            return fields.toArray(new String[0]);
                        } else if (current != '\r') {
                            field.append(current);
                        }
                    }
                    case 2 -> {
                        if (current == '"') state = 3;
                        else field.append(current);
                    }
                    default -> {
                        if (current == '"') {
                            field.append('"');
                            state = 2;
                        } else if (current == ',') {
                            fields.add(field.toString());
                            field.setLength(0);
                            state = 0;
                        } else if (current == '\n') {
                            fields.add(field.toString());
                            return fields.toArray(new String[0]);
                        } else if (current != '\r') {
                            field.append(current);
                            state = 1;
                        }
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}
