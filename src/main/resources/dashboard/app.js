const state = {
  lastSnapshot: null,
  loading: false,
};

const THEME_STORAGE_KEY = "jmusicbot.dashboard.theme";
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

function renderRequesters(snapshot) {
  const requesters = snapshot.requesters || [];
  if (!requesters.length) {
    els.requesterList.innerHTML = `<div class="empty-state">No requester stats yet</div>`;
    return;
  }

  els.requesterList.innerHTML = requesters.slice(0, 10).map((requester, index) => `
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

function renderSources(snapshot) {
  const sources = snapshot.sources || [];
  if (!sources.length) {
    els.sourceList.innerHTML = `<div class="empty-state">No source data yet</div>`;
    return;
  }

  const max = Math.max(...sources.map((source) => source.plays || 0), 1);
  els.sourceList.innerHTML = sources.map((source) => {
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
        <span>${formatMinutes(guild.playedMs)} min · ${number.format(guild.requesters || 0)} requesters · ${number.format(guild.skips || 0)} skips</span>
      </div>
      <div class="requester-stat">${number.format(guild.songs || 0)}</div>
    </div>
  `).join("");
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
  renderRequesters(snapshot);
  renderTracks(snapshot);
  renderSources(snapshot);
  renderTrackDiversity(snapshot);
  renderGuildList(snapshot);
  renderCharts(snapshot);
  renderTrendNotes(snapshot);
  renderEvents(snapshot);
}

async function loadSnapshot() {
  if (state.loading) return;
  state.loading = true;
  els.refreshButton.disabled = true;

  try {
    const response = await fetch("/api/snapshot", { cache: "no-store" });
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
els.refreshButton.addEventListener("click", loadSnapshot);
loadSnapshot();
setInterval(loadSnapshot, 5000);
