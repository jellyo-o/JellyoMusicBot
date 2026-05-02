const state = {
  lastSnapshot: null,
  loading: false,
  selectedHistoryGuild: "global",
  selectedServerId: "",
};

const THEME_STORAGE_KEY = "jmusicbot.dashboard.theme";
const SNAPSHOT_URL = "/api/snapshot?requesters=50&recent=80&hours=24&tracks=50";
const number = new Intl.NumberFormat();

const els = {
  statusDot: document.getElementById("status-dot"),
  botStatus: document.getElementById("bot-status"),
  guildCount: document.getElementById("guild-count"),
  activeCount: document.getElementById("active-count"),
  dashboardPort: document.getElementById("dashboard-port"),
  serverList: document.getElementById("server-list"),
  lastUpdated: document.getElementById("last-updated"),
  themeToggle: document.getElementById("theme-toggle"),
  themeLabel: document.getElementById("theme-label"),
  refreshButton: document.getElementById("refresh-button"),
  songsPlayed: document.getElementById("songs-played"),
  minutesPlayed: document.getElementById("minutes-played"),
  skippedSongs: document.getElementById("skipped-songs"),
  uniqueRequesters: document.getElementById("unique-requesters"),
  topSong: document.getElementById("top-song"),
  topSongMeta: document.getElementById("top-song-meta"),
  completionRate: document.getElementById("completion-rate"),
  skipRate: document.getElementById("skip-rate"),
  averagePlay: document.getElementById("average-play"),
  liveCount: document.getElementById("live-count"),
  nowPlayingBody: document.getElementById("now-playing-body"),
  requesterList: document.getElementById("requester-list"),
  trackList: document.getElementById("track-list"),
  skippedTrackList: document.getElementById("skipped-track-list"),
  sourceList: document.getElementById("source-list"),
  trackDiversity: document.getElementById("track-diversity"),
  guildList: document.getElementById("guild-list"),
  minutesChart: document.getElementById("minutes-chart"),
  dailyChart: document.getElementById("daily-chart"),
  hourOfDayChart: document.getElementById("hour-of-day-chart"),
  trendNotes: document.getElementById("trend-notes"),
  eventList: document.getElementById("event-list"),
  historyServerSelect: document.getElementById("history-server-select"),
  historyList: document.getElementById("history-list"),
  historyCount: document.getElementById("history-count"),
  serverSelect: document.getElementById("server-select"),
  serverDetailTitle: document.getElementById("server-detail-title"),
  serverDetailMeta: document.getElementById("server-detail-meta"),
  serverSummaryStats: document.getElementById("server-summary-stats"),
  serverTrackList: document.getElementById("server-track-list"),
  serverRequesterList: document.getElementById("server-requester-list"),
  serverSourceList: document.getElementById("server-source-list"),
  serverHistoryList: document.getElementById("server-history-list"),
};

function setTheme(theme, persist = true) {
  const nextTheme = theme === "light" ? "light" : "dark";
  document.documentElement.dataset.theme = nextTheme;
  els.themeToggle.checked = nextTheme === "light";
  els.themeLabel.textContent = nextTheme === "light" ? "Light" : "Dark";
  els.themeToggle.setAttribute("aria-label", nextTheme === "light" ? "Use dark mode" : "Use light mode");

  if (persist) {
    try {
      localStorage.setItem(THEME_STORAGE_KEY, nextTheme);
    } catch (error) {
      // Theme persistence is optional; the toggle should still work for the current page.
    }
  }
}

function setupTheme() {
  const initialTheme = document.documentElement.dataset.theme || "dark";
  setTheme(initialTheme, false);
  els.themeToggle.addEventListener("change", () => {
    setTheme(els.themeToggle.checked ? "light" : "dark");
  });
}

function setupTabs() {
  const buttons = Array.from(document.querySelectorAll("[data-tab]"));
  const panels = Array.from(document.querySelectorAll("[data-tab-panel]"));
  buttons.forEach((button) => {
    button.addEventListener("click", () => {
      const tab = button.dataset.tab;
      buttons.forEach((item) => {
        const active = item === button;
        item.classList.toggle("active", active);
        item.setAttribute("aria-selected", active ? "true" : "false");
      });
      panels.forEach((panel) => {
        const active = panel.dataset.tabPanel === tab;
        panel.classList.toggle("active", active);
        panel.hidden = !active;
      });
      button.scrollIntoView({ block: "nearest", inline: "center" });
    });
  });
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function formatMs(ms) {
  const totalSeconds = Math.max(0, Math.round((ms || 0) / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
  }
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

function formatMinutes(ms) {
  return number.format(Math.round((ms || 0) / 60000));
}

function formatPercent(value) {
  return `${Math.round((value || 0) * 100)}%`;
}

function sourceName(value) {
  const source = value || "unknown";
  return source.charAt(0).toUpperCase() + source.slice(1);
}

function timeAgo(ts) {
  if (!ts) return "unknown";
  const seconds = Math.max(0, Math.round((Date.now() - ts) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

function formatDateTime(ts) {
  if (!ts) return "--";
  return new Date(ts).toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

function titleCase(value) {
  return String(value || "")
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/\b\w/g, (char) => char.toUpperCase());
}

function historyStatus(row) {
  if (!row.endedAt) return "Playing";
  if (row.skipped) return row.skipType ? `Skipped · ${titleCase(row.skipType)}` : "Skipped";
  return titleCase(row.endReason || "Ended");
}

function serverDetails(snapshot) {
  return snapshot.serverDetails || [];
}

function syncServerSelect(select, details, currentValue, includeGlobal, globalLabel) {
  const validIds = new Set(details.map((detail) => String(detail.id)));
  const fallback = includeGlobal ? "global" : (details[0] ? String(details[0].id) : "");
  const selected = (includeGlobal && currentValue === "global") || validIds.has(String(currentValue))
    ? String(currentValue)
    : fallback;
  const options = [];
  if (includeGlobal) {
    options.push(`<option value="global">${escapeHtml(globalLabel)}</option>`);
  }
  options.push(...details.map((detail) => `
    <option value="${escapeHtml(detail.id)}">${escapeHtml(detail.name || "Unknown server")}</option>
  `));
  select.innerHTML = options.join("");
  select.value = selected;
  select.disabled = !details.length && !includeGlobal;
  return selected;
}

function setStatus(snapshot, failed = false) {
  const bot = snapshot?.bot || {};
  const status = failed ? "OFFLINE" : (bot.jdaStatus || "STARTING");
  els.botStatus.textContent = status;
  els.guildCount.textContent = number.format(bot.guildCount || 0);
  els.dashboardPort.textContent = bot.port || "--";
  els.statusDot.classList.toggle("online", status === "CONNECTED");
  els.statusDot.classList.toggle("offline", failed);
}

function renderSummary(snapshot) {
  const summary = snapshot.summary || {};
  const liveCount = (snapshot.live || []).length;
  els.songsPlayed.textContent = number.format(summary.songsPlayed || 0);
  els.minutesPlayed.textContent = number.format(summary.minutesPlayed || 0);
  els.skippedSongs.textContent = number.format(summary.skippedSongs || 0);
  els.uniqueRequesters.textContent = number.format(summary.uniqueRequesters || 0);
  els.activeCount.textContent = number.format(liveCount);
  els.liveCount.textContent = `${number.format(liveCount)} active`;
  els.lastUpdated.textContent = `Updated ${new Date(snapshot.generatedAt || Date.now()).toLocaleTimeString()}`;
}

function renderInsights(snapshot) {
  const summary = snapshot.summary || {};
  const topTrack = (snapshot.tracks || [])[0];
  if (topTrack) {
    els.topSong.textContent = topTrack.title || "Unknown track";
    els.topSongMeta.textContent = `${number.format(topTrack.plays || 0)} plays · ${formatMinutes(topTrack.playedMs)} min · ${sourceName(topTrack.source)}`;
  } else {
    els.topSong.textContent = "No plays yet";
    els.topSongMeta.textContent = "Waiting for playback";
  }
  els.completionRate.textContent = formatPercent(summary.completionRate);
  els.skipRate.textContent = formatPercent(summary.skipRate);
  els.averagePlay.textContent = formatMs(summary.averagePlayedMs || 0);
}

function renderServers(snapshot) {
  const guilds = snapshot.guilds || [];
  if (!guilds.length) {
    els.serverList.innerHTML = `<div class="empty-state">No server playback yet</div>`;
    return;
  }

  els.serverList.innerHTML = guilds.slice(0, 6).map((guild) => `
    <div class="server-row">
      <strong>${escapeHtml(guild.name)}</strong>
      <span>${number.format(guild.songs || 0)} songs · ${formatMinutes(guild.playedMs)} min · ${number.format(guild.requesters || 0)} requesters</span>
    </div>
  `).join("");
}

function renderNowPlaying(snapshot) {
  const live = snapshot.live || [];
  if (!live.length) {
    els.nowPlayingBody.innerHTML = `
      <tr>
        <td colspan="5"><div class="empty-state">Nothing is playing right now</div></td>
      </tr>
    `;
    return;
  }

  els.nowPlayingBody.innerHTML = live.map((item) => {
    const track = item.track || {};
    const guild = item.guild || {};
    const requester = item.requester || {};
    const duration = track.durationMs || 0;
    const position = track.positionMs || 0;
    const progress = duration > 0 ? Math.min(100, Math.round((position / duration) * 100)) : 100;
    return `
      <tr>
        <td data-label="Server">
          <div class="cell-main">${escapeHtml(guild.name || "Unknown server")}</div>
          <div class="cell-sub">${escapeHtml(item.channel?.name || "No voice channel")}</div>
        </td>
        <td data-label="Track">
          <div class="track-title">${escapeHtml(track.title || "Unknown track")}</div>
          <div class="cell-sub">${escapeHtml(track.author || track.source || "Unknown source")}</div>
        </td>
        <td data-label="Requester">
          <div class="cell-main">${escapeHtml(requester.name || "Unknown")}</div>
          <div class="cell-sub">${item.paused ? "Paused" : "Playing"} · ${number.format(item.votes || 0)} skip votes</div>
        </td>
        <td data-label="Progress">
          <div class="progress-track"><div class="progress-fill" style="width:${progress}%"></div></div>
          <div class="cell-sub">${formatMs(position)} / ${duration ? formatMs(duration) : "live"}</div>
        </td>
        <td data-label="Queue">${number.format(item.queueSize || 0)}</td>
      </tr>
    `;
  }).join("");
}

function renderRequesterRows(target, requesters, emptyText) {
  if (!requesters.length) {
    target.innerHTML = `<div class="empty-state">${escapeHtml(emptyText)}</div>`;
    return;
  }

  target.innerHTML = requesters.slice(0, 10).map((requester, index) => `
    <div class="requester-row">
      <div class="rank">${index + 1}</div>
      <div>
        <strong>${escapeHtml(requester.name || "Unknown")}</strong>
        <span>${formatMinutes(requester.playedMs)} min · ${number.format(requester.skips || 0)} skips</span>
      </div>
      <div class="requester-stat">${number.format(requester.songs || 0)}</div>
    </div>
  `).join("");
}

function renderRequesters(snapshot) {
  renderRequesterRows(els.requesterList, snapshot.requesters || [], "No requester stats yet");
}

function renderTrackList(target, tracks, emptyText, skippedMode = false) {
  if (!tracks.length) {
    target.innerHTML = `<div class="empty-state">${escapeHtml(emptyText)}</div>`;
    return;
  }

  target.innerHTML = tracks.slice(0, 15).map((track, index) => `
    <div class="requester-row">
      <div class="rank">${index + 1}</div>
      <div>
        <strong>${escapeHtml(track.title || "Unknown track")}</strong>
        <span>${escapeHtml(track.author || sourceName(track.source))} · ${formatMinutes(track.playedMs)} min · ${number.format(track.requesters || 0)} requesters</span>
      </div>
      <div class="requester-stat">${number.format(skippedMode ? (track.skips || 0) : (track.plays || 0))}</div>
    </div>
  `).join("");
}

function renderTracks(snapshot) {
  const tracks = snapshot.tracks || [];
  const skippedTracks = snapshot.skippedTracks || [];
  renderTrackList(els.trackList, tracks, "No played tracks yet");
  renderTrackList(els.skippedTrackList, skippedTracks, "No skipped tracks yet", true);
}

function renderSourceRows(target, sources, emptyText) {
  if (!sources.length) {
    target.innerHTML = `<div class="empty-state">${escapeHtml(emptyText)}</div>`;
    return;
  }

  const max = Math.max(...sources.map((source) => source.plays || 0), 1);
  target.innerHTML = sources.map((source) => {
    const width = Math.max(3, Math.round(((source.plays || 0) / max) * 100));
    return `
      <div class="source-row">
        <div class="source-name">${escapeHtml(sourceName(source.source))}</div>
        <div class="source-bar"><div class="source-fill" style="width:${width}%"></div></div>
        <div class="source-meta">${number.format(source.plays || 0)} plays · ${formatMinutes(source.playedMs)} min</div>
      </div>
    `;
  }).join("");
}

function renderSources(snapshot) {
  renderSourceRows(els.sourceList, snapshot.sources || [], "No source data yet");
}

function renderTrackDiversity(snapshot) {
  const tracks = snapshot.tracks || [];
  const uniqueSources = new Set(tracks.map((track) => track.source || "unknown")).size;
  const totalTopPlays = tracks.reduce((sum, track) => sum + (track.plays || 0), 0);
  const totalTopSkips = tracks.reduce((sum, track) => sum + (track.skips || 0), 0);
  const multiServerTracks = tracks.filter((track) => (track.guilds || 0) > 1).length;
  els.trackDiversity.innerHTML = `
    <div class="stat-row"><strong>${number.format(tracks.length)}</strong><span>unique tracks in the leaderboard</span></div>
    <div class="stat-row"><strong>${number.format(uniqueSources)}</strong><span>sources represented by top tracks</span></div>
    <div class="stat-row"><strong>${number.format(multiServerTracks)}</strong><span>top tracks played across multiple servers</span></div>
    <div class="stat-row"><strong>${number.format(totalTopPlays)}</strong><span>plays captured by the current top track set</span></div>
    <div class="stat-row"><strong>${number.format(totalTopSkips)}</strong><span>skips captured by the current top track set</span></div>
  `;
}

function renderGuildList(snapshot) {
  const guilds = snapshot.guilds || [];
  if (!guilds.length) {
    els.guildList.innerHTML = `<div class="empty-state">No server playback yet</div>`;
    return;
  }

  els.guildList.innerHTML = guilds.map((guild, index) => `
    <div class="requester-row">
      <div class="rank">${index + 1}</div>
      <div>
        <strong>${escapeHtml(guild.name || "Unknown server")}</strong>
        <span>${formatMinutes(guild.playedMs)} min · ${number.format(guild.tracks || 0)} tracks · ${number.format(guild.requesters || 0)} requesters · ${formatPercent(guild.skipRate)} skipped</span>
      </div>
      <div class="requester-stat">${number.format(guild.songs || 0)}</div>
    </div>
  `).join("");
}

function renderHistoryRows(target, history, emptyText, showServer = true) {
  if (!history.length) {
    target.innerHTML = `<div class="empty-state">${escapeHtml(emptyText)}</div>`;
    return;
  }

  target.innerHTML = history.map((row) => {
    const track = row.track || {};
    const guild = row.guild || {};
    const channel = row.channel || {};
    const requester = row.requester || {};
    const status = historyStatus(row);
    const serverMeta = showServer ? `${escapeHtml(guild.name || "Unknown server")} · ` : "";
    const requesterName = requester.name || "Autoplay or unknown";
    return `
      <div class="history-row">
        <div class="history-main">
          <strong>${escapeHtml(track.title || "Unknown track")}</strong>
          <span>${escapeHtml(track.author || sourceName(track.source))} · ${serverMeta}${escapeHtml(requesterName)}</span>
          <small>${escapeHtml(channel.name || "No voice channel")} · ${escapeHtml(formatDateTime(row.startedAt))}</small>
        </div>
        <div class="history-metrics">
          <span class="status-pill ${row.skipped ? "warn" : (!row.endedAt ? "live" : "")}">${escapeHtml(status)}</span>
          <span>${formatMs(row.playedMs || 0)} played</span>
          <span>${row.endedAt ? timeAgo(row.endedAt) : "live now"}</span>
        </div>
      </div>
    `;
  }).join("");
}

function renderHistory(snapshot) {
  const details = serverDetails(snapshot);
  state.selectedHistoryGuild = syncServerSelect(
    els.historyServerSelect,
    details,
    state.selectedHistoryGuild,
    true,
    "Global"
  );
  const selectedDetail = details.find((detail) => String(detail.id) === state.selectedHistoryGuild);
  const history = state.selectedHistoryGuild === "global"
    ? (snapshot.history || [])
    : (selectedDetail?.history || []);
  els.historyCount.textContent = `${number.format(history.length)} ${history.length === 1 ? "session" : "sessions"}`;
  renderHistoryRows(
    els.historyList,
    history,
    state.selectedHistoryGuild === "global" ? "No song history yet" : "No song history for this server yet",
    state.selectedHistoryGuild === "global"
  );
}

function renderServerDetail(snapshot) {
  const details = serverDetails(snapshot);
  state.selectedServerId = syncServerSelect(els.serverSelect, details, state.selectedServerId, false, "");
  const detail = details.find((item) => String(item.id) === state.selectedServerId);
  if (!detail) {
    els.serverDetailTitle.textContent = "Server Stats";
    els.serverDetailMeta.textContent = "No server playback yet";
    els.serverSummaryStats.innerHTML = `<div class="empty-state">No server playback yet</div>`;
    renderTrackList(els.serverTrackList, [], "No server tracks yet");
    renderRequesterRows(els.serverRequesterList, [], "No server requesters yet");
    renderSourceRows(els.serverSourceList, [], "No server source data yet");
    renderHistoryRows(els.serverHistoryList, [], "No server song history yet", false);
    return;
  }

  const summary = detail.summary || {};
  els.serverDetailTitle.textContent = detail.name || "Unknown server";
  els.serverDetailMeta.textContent = `${number.format(summary.songs || 0)} songs · ${formatMinutes(summary.playedMs)} min · last played ${timeAgo(summary.lastPlayedAt)}`;
  els.serverSummaryStats.innerHTML = `
    <div class="server-stat"><span>Songs</span><strong>${number.format(summary.songs || 0)}</strong></div>
    <div class="server-stat"><span>Listened</span><strong>${formatMinutes(summary.playedMs)}m</strong></div>
    <div class="server-stat"><span>Unique Tracks</span><strong>${number.format(summary.tracks || 0)}</strong></div>
    <div class="server-stat"><span>Requesters</span><strong>${number.format(summary.requesters || 0)}</strong></div>
    <div class="server-stat"><span>Completion</span><strong>${formatPercent(summary.completionRate)}</strong></div>
    <div class="server-stat"><span>Skip Rate</span><strong>${formatPercent(summary.skipRate)}</strong></div>
    <div class="server-stat"><span>Avg Play</span><strong>${formatMs(summary.averagePlayedMs || 0)}</strong></div>
    <div class="server-stat"><span>Sources</span><strong>${number.format(summary.sources || 0)}</strong></div>
  `;
  renderTrackList(els.serverTrackList, detail.topTracks || [], "No server tracks yet");
  renderRequesterRows(els.serverRequesterList, detail.requesters || [], "No server requesters yet");
  renderSourceRows(els.serverSourceList, detail.sources || [], "No server source data yet");
  renderHistoryRows(els.serverHistoryList, detail.history || [], "No server song history yet", false);
}

function renderBarChart(target, buckets, emptyText, valueSelector, labelSelector) {
  if (!buckets.length) {
    target.innerHTML = `<div class="empty-state">${escapeHtml(emptyText)}</div>`;
    return;
  }

  const max = Math.max(...buckets.map(valueSelector), 1);
  target.innerHTML = buckets.map((bucket) => {
    const value = valueSelector(bucket);
    const height = Math.max(4, Math.round((value / max) * 170));
    const label = labelSelector(bucket);
    return `
      <div class="bar" title="${escapeHtml(formatMinutes(bucket.playedMs))} minutes · ${number.format(bucket.songs || 0)} songs">
        <div class="bar-fill" style="height:${height}px"></div>
        <span>${escapeHtml(label)}</span>
      </div>
    `;
  }).join("");
}

function renderCharts(snapshot) {
  renderBarChart(
    els.minutesChart,
    snapshot.hourly || [],
    "No hourly data yet",
    (bucket) => bucket.playedMs || 0,
    (bucket) => (bucket.bucket || "").slice(11, 13) || "--"
  );
  renderBarChart(
    els.dailyChart,
    snapshot.daily || [],
    "No daily data yet",
    (bucket) => bucket.playedMs || 0,
    (bucket) => (bucket.bucket || "").slice(5) || "--"
  );
  renderBarChart(
    els.hourOfDayChart,
    snapshot.hoursOfDay || [],
    "No hour-of-day data yet",
    (bucket) => bucket.playedMs || 0,
    (bucket) => bucket.hour || "--"
  );
}

function renderTrendNotes(snapshot) {
  const hourly = snapshot.hoursOfDay || [];
  const sources = snapshot.sources || [];
  const topHour = hourly.reduce((best, bucket) => !best || (bucket.playedMs || 0) > (best.playedMs || 0) ? bucket : best, null);
  const topSource = sources[0];
  const topSkipped = (snapshot.skippedTracks || [])[0];
  const daily = snapshot.daily || [];
  const bestDay = daily.reduce((best, bucket) => !best || (bucket.playedMs || 0) > (best.playedMs || 0) ? bucket : best, null);
  els.trendNotes.innerHTML = `
    <div class="stat-row"><strong>${topHour ? `${topHour.hour}:00` : "--"}</strong><span>busiest hour of day by listened minutes</span></div>
    <div class="stat-row"><strong>${topSource ? sourceName(topSource.source) : "--"}</strong><span>dominant source by play count</span></div>
    <div class="stat-row"><strong>${topSkipped ? escapeHtml(topSkipped.title || "Unknown track") : "--"}</strong><span>most skipped track</span></div>
    <div class="stat-row"><strong>${bestDay ? bestDay.bucket : "--"}</strong><span>strongest day in the last 14 days</span></div>
  `;
}

function renderEvents(snapshot) {
  const events = snapshot.recent || [];
  if (!events.length) {
    els.eventList.innerHTML = `<div class="empty-state">No playback events yet</div>`;
    return;
  }

  els.eventList.innerHTML = events.slice(0, 12).map((event) => `
    <div class="event-row">
      <div>
        <strong>${escapeHtml(event.trackTitle || event.detail || "Playback event")}</strong>
        <span><span class="event-type">${escapeHtml((event.type || "event").replaceAll("_", " "))}</span> · ${escapeHtml(event.guildName || "Unknown server")} · ${escapeHtml(event.userName || "system")}</span>
      </div>
      <span>${escapeHtml(timeAgo(event.createdAt))}</span>
    </div>
  `).join("");
}

function render(snapshot) {
  state.lastSnapshot = snapshot;
  setStatus(snapshot);
  renderSummary(snapshot);
  renderInsights(snapshot);
  renderServers(snapshot);
  renderNowPlaying(snapshot);
  renderHistory(snapshot);
  renderRequesters(snapshot);
  renderTracks(snapshot);
  renderSources(snapshot);
  renderTrackDiversity(snapshot);
  renderGuildList(snapshot);
  renderServerDetail(snapshot);
  renderCharts(snapshot);
  renderTrendNotes(snapshot);
  renderEvents(snapshot);
}

async function loadSnapshot() {
  if (state.loading) return;
  state.loading = true;
  els.refreshButton.disabled = true;

  try {
    const response = await fetch(SNAPSHOT_URL, { cache: "no-store" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    render(await response.json());
  } catch (error) {
    setStatus(state.lastSnapshot, true);
    els.lastUpdated.textContent = `Dashboard API unavailable`;
  } finally {
    state.loading = false;
    els.refreshButton.disabled = false;
  }
}

setupTheme();
setupTabs();
els.historyServerSelect.addEventListener("change", () => {
  state.selectedHistoryGuild = els.historyServerSelect.value;
  if (state.lastSnapshot) renderHistory(state.lastSnapshot);
});
els.serverSelect.addEventListener("change", () => {
  state.selectedServerId = els.serverSelect.value;
  if (state.lastSnapshot) renderServerDetail(state.lastSnapshot);
});
els.refreshButton.addEventListener("click", loadSnapshot);
loadSnapshot();
setInterval(loadSnapshot, 5000);
