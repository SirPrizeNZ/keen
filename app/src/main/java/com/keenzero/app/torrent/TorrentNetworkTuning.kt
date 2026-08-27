package com.keenzero.app.torrent

import org.libtorrent4j.SettingsPack
import org.libtorrent4j.swig.settings_pack

/**
 * Keeps a torrent session inside what the home router can hold, not what the box can open.
 *
 * The Wi-Fi drop a few minutes into a stream was never the peer count. Measured on the Mi Box
 * with `connectionsLimit` doing its job perfectly — `conn=36-39` against a cap of 40 — the
 * router's NAT table was carrying **655-855 tracked flows**, of which only ~36 were TCP
 * sockets. The other ~600 were UDP: DHT and uTP traffic leaving from a single unconnected
 * socket, invisible to `ss` because one socket serves every destination, but one NAT entry per
 * destination on the router, each held 30s (`nf_conntrack_udp_timeout`) or 120s once it
 * counts as a stream.
 *
 * The router eventually stopped answering ARP, and Android did what it must:
 *
 *     CMD_IP_REACHABILITY_FAILURE  FAILURE: LOST_PROVISIONING,
 *     NeighborEvent{...,192.168.68.1,NUD_FAILED,...}, reason: 2
 *
 * followed by `CMD_UNWANTED_NETWORK ... 1 0` — validation failed. RSSI was -52 dBm at
 * 1200 Mbps throughout and there was no supplicant disconnect, so the radio never dropped.
 * The link was fine; the peer on the other end of it had run out of table.
 *
 * `connectionsLimit` cannot help with this, which is why raising and lowering it never fixed
 * anything: it caps *concurrent TCP peers*, and concurrent TCP peers were never what filled
 * the table. What fills it is the **rate** at which flows are created and how long each one
 * lingers after it stops being useful.
 *
 * So that is what this throttles, and only that. Every setting here bounds churn or residency.
 * None of them lowers the number of peers a torrent may hold or the bandwidth it may use, so
 * steady-state throughput is unchanged — a stream that reached 40 peers still reaches 40
 * peers and still saturates the link, it just takes about five seconds to get there instead
 * of one. Deliberately left alone, because these are the ones that would cost real
 * performance rather than router state:
 *
 *  - `connectionsLimit` — each service keeps its own budget; that is the throughput dial.
 *  - `enable_outgoing_utp` / `enable_incoming_utp` — uTP is a large share of the UDP, but it
 *    is also how many peers connect, and it yields to other traffic more politely than TCP
 *    does. Forcing TCP would trade a router problem for a bufferbloat one.
 *  - `enable_upnp` / `enable_natpmp` — a port mapping is a handful of entries, not six
 *    hundred, and dropping inbound peers is a real cost for no meaningful saving.
 *
 * The rate and residency limits above turned out to be worth little on their own: measured
 * with all of them in force, the table still reached 935. Disabling DHT for one run and
 * changing nothing else took the same stream to **293, and flat instead of climbing**. The
 * churn was never peer connections — it is DHT, which keeps its routing table warm by
 * querying hundreds of distinct nodes whether or not the torrent needs anything, and each
 * of those is a NAT entry. `dht_upload_rate_limit` does not touch it: that caps replies we
 * send, not lookups we start.
 *
 * DHT is still the only way to reach a torrent with no working tracker, so it is not
 * disabled — it is made *temporary*. DHT runs from the start, does the discovery it is good
 * at, and is switched off once the torrent has a working set of peers (see [followSwarm]).
 * If those peers later dry up it comes back.
 *
 * The first attempt at this had it the other way around — off by default, switched on for a
 * torrent that had found nothing — and that was wrong in a way worth recording. A DHT
 * started from cold has to bootstrap before it can answer anything, which takes far longer
 * than the lookup it was started for. Magnets from sites whose trackers are dead, which are
 * exactly the ones that depend on DHT, went from resolving in seconds to timing out. The
 * trigger fired correctly; DHT simply cannot do its job from a standing start.
 *
 * The same run also came out faster — 4.2 MB/s against 1.6 MB/s at the same point of the
 * same file — because the box had been spending its radio and CPU on DHT rather than on
 * the film.
 *
 * Applied to both the streaming and the library-download session. Settings are re-asserted
 * on every mode change rather than set once, so a later partial `applySettings` cannot
 * quietly leave a session running on libtorrent's defaults.
 */
internal object TorrentNetworkTuning {

    /**
     * New outbound connection attempts per second, across all torrents.
     *
     * libtorrent defaults to 30, which is the single largest source of tracked flows: every
     * attempt creates a NAT entry whether or not a peer ever answers, and on a public swarm
     * most of them do not answer. At 8/s a session still fills a 40-peer budget in about five
     * seconds, which no viewer notices, while creating flows at under a third of the old rate.
     */
    private const val CONNECTION_SPEED = 8

    /**
     * Extra connection attempts allowed immediately when a torrent starts, beyond
     * [CONNECTION_SPEED].
     *
     * The default 30 is a deliberate land-rush to shorten time-to-first-piece, and it is also
     * the sharp spike at the front of every measured run. Ten keeps the fast start — the first
     * pieces come from the first few seeds to answer, not the thirtieth — without the spike.
     */
    private const val TORRENT_CONNECT_BOOST = 10

    /**
     * Seconds before an unanswered connection attempt is abandoned.
     *
     * This is residency rather than rate: a dead peer holds its NAT entry for the whole
     * timeout. The default 15s means one attempt per second sustains fifteen useless entries.
     * Eight is still far longer than any peer that is going to answer needs, and halves what
     * the dead ones occupy.
     */
    private const val PEER_CONNECT_TIMEOUT_SECONDS = 8

    /**
     * How many candidate peers a torrent remembers.
     *
     * The default is a few thousand, sized for a long-running seedbox that will eventually try
     * all of them. A session capped at 40 connections never needs more than a few hundred
     * candidates, and the surplus is pure intake for DHT and PEX to keep feeding. Sintel
     * reported `listPeers=207`; a large public swarm runs to thousands, and this is what stops
     * that scaling straight into the router.
     */
    private const val MAX_PEERLIST_SIZE = 500

    /**
     * Bytes per second the DHT may spend answering other nodes.
     *
     * Serving the DHT is pure overhead for a box that only ever wants to find its own swarm,
     * and each reply is another short-lived UDP flow. The default 8000 B/s is a good citizen's
     * budget for a machine that stays up; 2000 B/s still answers, just less eagerly. Lookups
     * we initiate are unaffected, so finding peers is as fast as before.
     */
    private const val DHT_UPLOAD_RATE_LIMIT = 2000

    /**
     * How often the worst-performing connected peers are dropped and replaced.
     *
     * libtorrent already vets its peers: every [PEER_TURNOVER_INTERVAL_SECONDS] it ranks the
     * connections by what they are actually delivering and disconnects the slowest
     * [PEER_TURNOVER_PERCENT], replacing them from the candidate list. The defaults are
     * written for a seedbox that stays up for days — every five minutes, four percent, and
     * only once the connection budget is 90% full — which over a two-minute wait to start a
     * film amounts to nothing at all.
     *
     * Measured on the Mi Box against a 6-seed swarm: 28-38 connections held against a budget
     * of 40, with 500 peer candidates queued behind them and no turnover in the whole
     * session. A connection to a peer that never sends a byte costs exactly as much of that
     * budget as one running at a megabyte, and holds it indefinitely.
     *
     * Fifteen seconds, fifteen percent, from 70% of the budget: a dead connection is now
     * given a quarter of a minute to prove itself rather than five minutes, and the swarm
     * this box ends up talking to is the fast end of what it found rather than the first
     * forty addresses that answered.
     *
     * Shortened from thirty once the per-peer read-out showed the fast peers surviving the
     * cull rather than being churned with the rest: the same three addresses held the top
     * of the ranking across samples, which is what says a shorter interval costs good
     * connections nothing. It is the floor for this setting on this box, though — every
     * replacement is a NAT flow closed and another opened, and that pressure is what took
     * the wifi down before, so this is watched rather than tuned further.
     */
    private const val PEER_TURNOVER_INTERVAL_SECONDS = 15

    /**
     * Percentage of connected peers replaced each interval. The default is 4.
     *
     * Fifteen is about five connections at this box's budget, which is enough to clear a
     * bad intake within two intervals without churning a working set: the peers that are
     * delivering rank at the top and are never the ones chosen.
     */
    private const val PEER_TURNOVER_PERCENT = 15

    /**
     * How full the connection budget must be before any peer is dropped. The default is 90.
     *
     * At 90 a session sitting on 33 of 40 connections — which is where this box actually
     * sits — never turns a single peer over, because it never looks full enough to bother.
     * Seventy makes the vetting apply at the occupancy the box really runs at.
     */
    private const val PEER_TURNOVER_CUTOFF_PERCENT = 70

    /**
     * Seconds a connected peer may send nothing before it is dropped. The default is 120.
     *
     * Turnover alone is a ranking: it removes the slowest fraction on a timer, whatever
     * the absolute numbers are. This is the floor underneath it — a peer that has sent
     * nothing at all for this long is not slow, it is not participating, and on a box with
     * forty slots and five hundred candidates the cheapest thing to do is take the slot
     * back and try someone else.
     *
     * Forty-five seconds rather than something shorter because being choked is normal and
     * temporary: a seed that has us queued will unchoke within a round or two of its own
     * choker (ten-second intervals by default), and a peer dropped mid-handshake is a slot
     * spent for nothing. This is comfortably past both, and a third of the default.
     */
    private const val PEER_TIMEOUT_SECONDS = 45

    /**
     * How long a peer that has been asked for a piece has to deliver it. The default is 20.
     *
     * The streaming case is not the seedbox case. A piece that has not arrived in fifteen
     * seconds has already cost more than the read-ahead window is worth, and the request
     * has to be re-issued to somebody else before the player runs out of buffer — which is
     * the whole difference between a stall the viewer sees and one they do not.
     */
    private const val PIECE_TIMEOUT_SECONDS = 15

    /** libtorrent's own defaults, restored by the kill switch. See [vettingDisabled]. */
    private const val DEFAULT_TURNOVER_INTERVAL_SECONDS = 300
    private const val DEFAULT_TURNOVER_PERCENT = 4
    private const val DEFAULT_TURNOVER_CUTOFF_PERCENT = 90
    private const val DEFAULT_PEER_TIMEOUT_SECONDS = 120
    private const val DEFAULT_PIECE_TIMEOUT_SECONDS = 20

    /**
     * Apply the router-friendly throttles to [pack], returning it for chaining.
     *
     * Additive: nothing here touches a setting a caller has set or may set, so it composes
     * with each session's own connection budget.
     */
    /**
     * Kill switch for the peer vetting, in the style of the bridge's other diagnostic
     * flags: `adb shell touch /data/local/tmp/keen_no_peer_turnover` and restart the
     * stream.
     *
     * It exists to make the vetting falsifiable. "Sending peers went from 7 to 35" is only
     * evidence if the alternative was measured on the same box, against the same swarm, at
     * the same time of day — and a swarm drifts enough over an hour that two builds run
     * back to back prove very little. A flag that can be toggled between runs makes an
     * interleaved A/B possible without reinstalling, which is the only design that
     * controls for the swarm changing underneath the measurement.
     *
     * With it set, every value below reverts to libtorrent's own default, so the flag
     * reproduces the behaviour that shipped before this work rather than some third thing.
     */
    private fun vettingDisabled(): Boolean =
        java.io.File("/data/local/tmp", "keen_no_peer_turnover").exists()

    fun apply(pack: SettingsPack): SettingsPack = pack
        .setInteger(settings_pack.int_types.connection_speed.swigValue(), CONNECTION_SPEED)
        .setInteger(
            settings_pack.int_types.torrent_connect_boost.swigValue(),
            TORRENT_CONNECT_BOOST,
        )
        .setInteger(
            settings_pack.int_types.peer_connect_timeout.swigValue(),
            PEER_CONNECT_TIMEOUT_SECONDS,
        )
        .setInteger(settings_pack.int_types.max_peerlist_size.swigValue(), MAX_PEERLIST_SIZE)
        // Drop the peers that are not delivering and take new ones from the candidate
        // list; see [PEER_TURNOVER_INTERVAL_SECONDS]. Costs no new NAT flows on balance —
        // each disconnect frees a tracked flow before its replacement opens one. Stated
        // either way round rather than simply skipped when the flag is set, so a session
        // running with the vetting off is running libtorrent's documented defaults and not
        // whatever a previous applySettings happened to leave behind.
        .setInteger(
            settings_pack.int_types.peer_turnover_interval.swigValue(),
            if (vettingDisabled()) DEFAULT_TURNOVER_INTERVAL_SECONDS
            else PEER_TURNOVER_INTERVAL_SECONDS,
        )
        .setInteger(
            settings_pack.int_types.peer_turnover.swigValue(),
            if (vettingDisabled()) DEFAULT_TURNOVER_PERCENT else PEER_TURNOVER_PERCENT,
        )
        .setInteger(
            settings_pack.int_types.peer_turnover_cutoff.swigValue(),
            if (vettingDisabled()) DEFAULT_TURNOVER_CUTOFF_PERCENT
            else PEER_TURNOVER_CUTOFF_PERCENT,
        )
        // The floor under the turnover ranking: a connection that has sent nothing for
        // this long is not slow, it is idle, and the slot is worth more than it is.
        .setInteger(
            settings_pack.int_types.peer_timeout.swigValue(),
            if (vettingDisabled()) DEFAULT_PEER_TIMEOUT_SECONDS else PEER_TIMEOUT_SECONDS,
        )
        .setInteger(
            settings_pack.int_types.piece_timeout.swigValue(),
            if (vettingDisabled()) DEFAULT_PIECE_TIMEOUT_SECONDS else PIECE_TIMEOUT_SECONDS,
        )
        .setInteger(
            settings_pack.int_types.dht_upload_rate_limit.swigValue(),
            DHT_UPLOAD_RATE_LIMIT,
        )
        // Local service discovery is multicast chatter looking for peers on the home LAN.
        // There are none — the only BitTorrent client on this network is this box — so it
        // buys nothing and the router forwards it anyway.
        .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), false)
        // On to begin with. DHT is what finds a magnet's swarm when its trackers are dead,
        // and it is only useful if it is already bootstrapped by the time it is asked —
        // see RETIRE_DHT_AFTER_PEERS.
        .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)

    /**
     * How many connected peers count as "this torrent no longer needs DHT".
     *
     * DHT earns its cost during discovery and stops earning it immediately afterwards. Once
     * a torrent holds a working set of peers, every further DHT query is maintenance traffic
     * for a lookup nobody is waiting on — and that maintenance is what fills the router.
     *
     * Eight is comfortably more than a stream needs to keep going and low enough that a
     * modest swarm still reaches it, so DHT is retired within seconds on a healthy torrent
     * and stays on for one that is genuinely struggling.
     */
    private const val RETIRE_DHT_AFTER_PEERS = 8

    /**
     * How long a torrent may find nothing before DHT is brought back.
     *
     * Only reached by a torrent whose peers have dried up after DHT was retired. Twelve
     * seconds is long enough that ordinary churn between pieces does not trip it and short
     * enough to recover well inside the buffer watchdog's thirty.
     */
    private const val DHT_STARVED_MS = 12_000L

    /**
     * Follow the swarm: retire DHT once a torrent is healthy, restore it if it starves.
     *
     * This is the whole router fix, and it is deliberately not "DHT off by default". A
     * session that starts without DHT has to bootstrap one from cold at the exact moment it
     * has discovered it needs peers, which takes far longer than the lookup it was wanted
     * for — magnets from sites with dead trackers went from resolving in seconds to timing
     * out. DHT therefore starts running, does its job, and is switched off once it is no
     * longer the thing finding peers.
     *
     * That still removes the load that mattered. The Wi-Fi failure took minutes to appear
     * because it needed the table to fill; DHT now runs for the few seconds of discovery
     * at the start of a stream rather than for its whole length, and the entries it leaves
     * behind age out while the film plays.
     *
     * [peers] is the connected peer count, or 0 while a magnet is still without metadata —
     * having no metadata means no peer has been useful yet, whatever is connected.
     * [wanted] is whether DHT is currently on, as the caller last recorded it. Returns the
     * state DHT should now be in, which the caller stores and passes back next tick.
     */
    fun followSwarm(
        session: SessionHandle,
        peers: Int,
        wanted: Boolean,
        starvedForMs: Long,
    ): Boolean {
        val target = when {
            wanted && peers >= RETIRE_DHT_AFTER_PEERS -> false
            !wanted && peers == 0 && starvedForMs >= DHT_STARVED_MS -> true
            else -> wanted
        }
        if (target == wanted) return wanted
        return runCatching {
            session.applySettings(
                SettingsPack()
                    .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), target),
            )
            target
        }.getOrDefault(wanted)
    }

    /** The subset of a session this file needs, so both session types can be passed in. */
    fun interface SessionHandle {
        fun applySettings(pack: SettingsPack)
    }

    /** Summary for the log, so a session's real limits are visible in a bug report. */
    val summary: String
        get() = "connectionSpeed=$CONNECTION_SPEED boost=$TORRENT_CONNECT_BOOST " +
            "connectTimeout=${PEER_CONNECT_TIMEOUT_SECONDS}s peerlist=$MAX_PEERLIST_SIZE " +
            "dhtUp=$DHT_UPLOAD_RATE_LIMIT lsd=off dht=until-${RETIRE_DHT_AFTER_PEERS}-peers " +
            if (vettingDisabled()) {
                "vetting=OFF turnover=$DEFAULT_TURNOVER_PERCENT%/" +
                    "${DEFAULT_TURNOVER_INTERVAL_SECONDS}s@$DEFAULT_TURNOVER_CUTOFF_PERCENT% " +
                    "peerTimeout=${DEFAULT_PEER_TIMEOUT_SECONDS}s " +
                    "pieceTimeout=${DEFAULT_PIECE_TIMEOUT_SECONDS}s"
            } else {
                "vetting=ON turnover=$PEER_TURNOVER_PERCENT%/${PEER_TURNOVER_INTERVAL_SECONDS}s" +
                    "@$PEER_TURNOVER_CUTOFF_PERCENT% peerTimeout=${PEER_TIMEOUT_SECONDS}s " +
                    "pieceTimeout=${PIECE_TIMEOUT_SECONDS}s"
            }
}
