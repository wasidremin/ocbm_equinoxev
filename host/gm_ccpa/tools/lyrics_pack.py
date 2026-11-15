#!/usr/bin/env python3
"""lyrics_pack.py: build the lyrics package the car downloads over Wi-Fi.

The app looks lyrics up one song at a time on lrclib.net, which costs the car's data and needs a
connection. This builds, on the home server, one file with the timed lyrics for the songs the owner
is likely to play, which the receiver offers at `GET <base>/lyrics?car=<id>` and the app takes only
on Wi-Fi or a hotspot (`lyrics/LyricsPack.kt`). Three lists feed it:

- **The year-end charts**: Billboard's Year-End Hot 100 for each year from 1980, read from
  Wikipedia's list pages. Titles and artists only, so each song is searched on lrclib and up to
  three versions of different length are kept: the single and the album cut are timed differently.
- **The owner's listening history**: Spotify's "extended streaming history" export (Account ▸ Privacy,
  a set of `Streaming_History_Audio_*.json`), every song played for 30 s or more. The export has no
  lengths, so a song's is taken from its full plays (the commonest `ms_played` that ended
  `trackdone`); without one it is searched like a chart entry.
- **The owner's Spotify library**: liked songs and the playlists they own or collaborate on (Spotify
  no longer returns the items of anyone else's playlist to a development-mode app). Signed in once
  with their own Spotify developer app, Authorization Code with PKCE, no client secret.

Stdlib only. Everything it keeps lives outside the repository:

    ~/.config/gmccpa-lyrics/spotify.json   client id and refresh token (mode 600)
    ~/.local/share/gmccpa-lyrics/state.sqlite   the song lists and every lrclib answer, found or not

so a weekly run asks lrclib only about songs it has not seen, and about a song it lacked no more
than once a month. lrclib is asked slowly ([PAUSE_S] between requests) and by name, as it requests.

    lyrics_pack.py charts                      fetch the year-end lists
    lyrics_pack.py history <file-or-dir>...    read a streaming-history export
    lyrics_pack.py spotify-login <client-id>   print the link to approve; then
    lyrics_pack.py spotify-code '<address the browser ended on>'
    lyrics_pack.py spotify                     read the library (after a login)
    lyrics_pack.py build                       look up what is missing and write the package
    lyrics_pack.py weekly                      charts (new years only), spotify if signed in, build
    lyrics_pack.py status

The package (`--out`, default /opt/stacks/cloudbridge-log/lyrics/pack.ndjson.gz) is gzip NDJSON: a
first line `{"v":1,"id":…,"built":…,"songs":…}` and then `{"t":title,"a":artist,"d":seconds,"lrc":…}`
per version. Nothing here prints a song's name unless asked with --verbose.
"""
import argparse
import base64
import gzip
import hashlib
import html.parser
import json
import os
import re
import secrets
import sqlite3
import sys
import tempfile
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = "GM-CCPA lyrics-pack (https://github.com/wasidremin/ocbm_equinoxev)"
LRCLIB = "https://lrclib.net/api"
WIKI = "https://en.wikipedia.org/w/api.php"
SPOTIFY_ACCOUNTS = "https://accounts.spotify.com"
SPOTIFY_API = "https://api.spotify.com/v1"
SCOPES = "user-library-read playlist-read-private playlist-read-collaborative"
DEFAULT_REDIRECT = "http://127.0.0.1:8888/callback"
FIRST_YEAR = 1980
PAUSE_S = 0.6
MATCH_SECONDS = 3.0
MAX_VERSIONS = 3
RETRY_NONE_AFTER_S = 30 * 86400
CONFIG_DIR = os.path.expanduser("~/.config/gmccpa-lyrics")
STATE_DIR = os.path.expanduser("~/.local/share/gmccpa-lyrics")
DEFAULT_OUT = "/opt/stacks/cloudbridge-log/lyrics/pack.ndjson.gz"


# --------------------------------------------------------------------------------------------------
# Naming a song the same way the app does (lyrics/LyricsPack.kt `baseTitle` / `artistTokens`)
# --------------------------------------------------------------------------------------------------

def _plain(s):
    """Lower case, accents off, everything but letters and digits a space."""
    s = unicodedata.normalize("NFKD", s.lower())
    out = "".join(c if c.isalnum() else " " for c in s if unicodedata.category(c)[0] != "M")
    return " ".join(out.split())


def base_title(title):
    """The title without what a release adds after it: "Song - Remastered 2012", "Song (feat. X)"."""
    t = title.strip()
    cuts = [i for i in (t.find(" - "), t.find("("), t.find("[")) if i > 0]
    head = t[:min(cuts)] if cuts else t
    return _plain(head) or _plain(t) or t.strip().lower()


STOP = {"the", "and", "feat", "featuring", "with", "vs", "ft"}


def artist_tokens(artist):
    """The words of an artist line that can tell one act from another."""
    return {w for w in _plain(artist).split() if len(w) >= 3 and w not in STOP}


def same_artist(a, b):
    ta, tb = artist_tokens(a), artist_tokens(b)
    if not ta or not tb:
        return _plain(a) == _plain(b)
    return bool(ta & tb)


def first_artist(artist):
    """Spotify joins a song's artists with commas; lrclib files most under the first."""
    return re.split(r", | featuring | feat\. | feat | with | and | & | x ", artist, maxsplit=1, flags=re.I)[0].strip()


# --------------------------------------------------------------------------------------------------
# State
# --------------------------------------------------------------------------------------------------

def db():
    os.makedirs(STATE_DIR, mode=0o700, exist_ok=True)
    con = sqlite3.connect(os.path.join(STATE_DIR, "state.sqlite"))
    con.executescript("""
        CREATE TABLE IF NOT EXISTS song (
            id INTEGER PRIMARY KEY, source TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL,
            album TEXT NOT NULL DEFAULT '', dur INTEGER NOT NULL DEFAULT 0,
            UNIQUE (title, artist, dur));
        CREATE TABLE IF NOT EXISTS answer (
            song INTEGER NOT NULL REFERENCES song(id), dur INTEGER NOT NULL, lrc TEXT,
            checked INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS answer_song ON answer(song);
        CREATE TABLE IF NOT EXISTS chart_year (year INTEGER PRIMARY KEY, songs INTEGER NOT NULL);
    """)
    return con


def add_song(con, source, title, artist, album="", dur=0):
    title, artist = title.strip(), artist.strip()
    if not title or not artist:
        return False
    cur = con.execute("INSERT OR IGNORE INTO song (source, title, artist, album, dur) VALUES (?,?,?,?,?)",
                      (source, title, artist, album.strip(), int(dur)))
    return cur.rowcount > 0


def http(url, data=None, headers=None, timeout=30):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": USER_AGENT, **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


# --------------------------------------------------------------------------------------------------
# The year-end charts
# --------------------------------------------------------------------------------------------------

class _Tables(html.parser.HTMLParser):
    """Every wikitable of a page as rows of cell texts; a quoted title keeps its quotes off."""

    def __init__(self):
        super().__init__()
        self.tables, self._table, self._row, self._cell, self._depth = [], None, None, None, 0

    def handle_starttag(self, tag, attrs):
        if tag == "table":
            self._depth += 1
            if self._depth == 1 and "wikitable" in (dict(attrs).get("class") or ""):
                self._table = []
        elif self._table is not None and self._depth == 1:
            if tag == "tr":
                self._row = []
            elif tag in ("td", "th") and self._row is not None:
                self._cell = []
            elif tag == "sup":
                self._skip = True

    def handle_endtag(self, tag):
        if tag == "table":
            if self._depth == 1 and self._table is not None:
                self.tables.append(self._table)
                self._table = None
            self._depth -= 1
        elif self._table is not None and self._depth == 1:
            if tag in ("td", "th") and self._cell is not None and self._row is not None:
                self._row.append(" ".join("".join(self._cell).split()))
                self._cell = None
            elif tag == "tr" and self._row is not None:
                self._table.append(self._row)
                self._row = None
            elif tag == "sup":
                self._skip = False

    _skip = False

    def handle_data(self, data):
        if self._cell is not None and not self._skip:
            self._cell.append(data)


def chart_rows(page_html):
    """(title, artist) for each row of the year's list: the widest table that starts at number 1."""
    p = _Tables()
    p.feed(page_html)
    best = []
    for table in p.tables:
        rows = []
        for cells in table:
            if len(cells) < 3 or not re.fullmatch(r"\d{1,3}", cells[0].strip()):
                continue
            title = cells[1].strip().strip('"“”').strip()
            # Two songs sharing a place are written "A" / "B"; the first names the entry.
            title = re.split(r'["”]\s*/\s*["“]', title)[0].strip('"“” ')
            rows.append((title, cells[2].strip()))
        if len(rows) > len(best):
            best = rows
    return best


def cmd_charts(args):
    con = db()
    last = time.gmtime().tm_year - 1
    done = {y for (y,) in con.execute("SELECT year FROM chart_year")}
    added = 0
    for year in range(FIRST_YEAR, last + 1):
        if year in done and not args.again:
            continue
        q = urllib.parse.urlencode({"action": "parse", "format": "json", "prop": "text", "redirects": 1,
                                    "page": f"Billboard Year-End Hot 100 singles of {year}"})
        try:
            page = json.loads(http(f"{WIKI}?{q}"))["parse"]["text"]["*"]
        except (KeyError, urllib.error.URLError, json.JSONDecodeError) as e:
            print(f"{year}: not read ({type(e).__name__})")
            continue
        rows = chart_rows(page)
        if len(rows) < 80:
            print(f"{year}: only {len(rows)} rows read, left for another run")
            continue
        new = sum(add_song(con, f"chart{year}", t, a) for t, a in rows)
        con.execute("INSERT OR REPLACE INTO chart_year (year, songs) VALUES (?,?)", (year, len(rows)))
        con.commit()
        added += new
        print(f"{year}: {len(rows)} songs, {new} new")
        time.sleep(PAUSE_S)
    print(f"charts: {added} songs added; {con.execute('SELECT count(*) FROM chart_year').fetchone()[0]} years held")


# --------------------------------------------------------------------------------------------------
# The listening history export
# --------------------------------------------------------------------------------------------------

MIN_PLAY_MS = 30_000


def history_songs(entries):
    """
    (title, artist, album, seconds) for each song of an export played MIN_PLAY_MS or more. The
    length is the commonest full play (`trackdone`), 0 when it was never played through.
    """
    played, album, full = {}, {}, {}
    for e in entries:
        title, artist = e.get("master_metadata_track_name"), e.get("master_metadata_album_artist_name")
        if not title or not artist:
            continue   # a podcast episode, a video, or a song Spotify no longer names
        key = (title, artist)
        ms = e.get("ms_played") or 0
        if ms >= MIN_PLAY_MS:
            played[key] = played.get(key, 0) + 1
            album.setdefault(key, e.get("master_metadata_album_album_name") or "")
        if e.get("reason_end") == "trackdone" and ms >= MIN_PLAY_MS:
            lengths = full.setdefault(key, {})
            sec = round(ms / 1000)
            lengths[sec] = lengths.get(sec, 0) + 1
    out = []
    for key in played:
        lengths = full.get(key)
        dur = max(lengths, key=lambda sec: (lengths[sec], sec)) if lengths else 0
        out.append((key[0], key[1], album[key], dur))
    return out


def cmd_history(args):
    files = []
    for path in args.paths:
        if os.path.isdir(path):
            files += [os.path.join(path, f) for f in sorted(os.listdir(path))]
        else:
            files.append(path)
    files = [f for f in files if re.search(r"Streaming_History_Audio.*\.json$", os.path.basename(f))]
    entries = []
    for f in files:
        with open(f, encoding="utf-8") as fh:
            entries += json.load(fh)
    songs = history_songs(entries)
    con = db()
    new = sum(add_song(con, "history", *song) for song in songs)
    con.commit()
    print(f"history: {len(files)} files, {len(entries)} plays, {len(songs)} songs played {MIN_PLAY_MS // 1000} s or more "
          f"({sum(1 for s in songs if s[3])} with a length), {new} new")


# --------------------------------------------------------------------------------------------------
# Spotify
# --------------------------------------------------------------------------------------------------

def _config_path():
    return os.path.join(CONFIG_DIR, "spotify.json")


def _load_config():
    try:
        with open(_config_path(), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return {}


def _save_config(cfg):
    os.makedirs(CONFIG_DIR, mode=0o700, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=CONFIG_DIR, prefix=".spotify-")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(cfg, f)
    os.chmod(tmp, 0o600)
    os.replace(tmp, _config_path())


def cmd_spotify_login(args):
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(12)
    cfg = _load_config()
    cfg.update({"client_id": args.client_id, "redirect": args.redirect, "verifier": verifier, "state": state})
    _save_config(cfg)
    q = urllib.parse.urlencode({"client_id": args.client_id, "response_type": "code", "redirect_uri": args.redirect,
                                "code_challenge_method": "S256", "code_challenge": challenge, "scope": SCOPES,
                                "state": state})
    print("1. The Spotify app's Redirect URIs (developer dashboard) must include:")
    print(f"     {args.redirect}")
    print("2. Open this link, sign in and approve:")
    print(f"     {SPOTIFY_ACCOUNTS}/authorize?{q}")
    print("3. The browser ends on a page that does not load. Copy its whole address and run:")
    print("     lyrics_pack.py spotify-code '<that address>'")


def cmd_spotify_code(args):
    cfg = _load_config()
    if not cfg.get("verifier"):
        sys.exit("run spotify-login first")
    raw = args.address.strip()
    q = urllib.parse.parse_qs(urllib.parse.urlparse(raw).query) if "?" in raw else {"code": [raw]}
    if q.get("error"):
        sys.exit(f"Spotify said: {q['error'][0]}")
    if "state" in q and q["state"][0] != cfg.get("state"):
        sys.exit("that address is from a different login link; run spotify-login again")
    code = (q.get("code") or [""])[0]
    if not code:
        sys.exit("no code in that address")
    body = urllib.parse.urlencode({"grant_type": "authorization_code", "code": code, "redirect_uri": cfg["redirect"],
                                   "client_id": cfg["client_id"], "code_verifier": cfg["verifier"]}).encode()
    try:
        tok = json.loads(http(f"{SPOTIFY_ACCOUNTS}/api/token", data=body,
                              headers={"Content-Type": "application/x-www-form-urlencoded"}))
    except urllib.error.HTTPError as e:
        sys.exit(f"Spotify refused the code (HTTP {e.code}); run spotify-login again for a fresh link")
    cfg = {"client_id": cfg["client_id"], "redirect": cfg["redirect"], "refresh_token": tok["refresh_token"]}
    _save_config(cfg)
    print("signed in; the refresh token is kept in", _config_path())


def _access_token():
    cfg = _load_config()
    if not cfg.get("refresh_token"):
        return None
    body = urllib.parse.urlencode({"grant_type": "refresh_token", "refresh_token": cfg["refresh_token"],
                                   "client_id": cfg["client_id"]}).encode()
    tok = json.loads(http(f"{SPOTIFY_ACCOUNTS}/api/token", data=body,
                          headers={"Content-Type": "application/x-www-form-urlencoded"}))
    # A PKCE refresh token is replaced each time it is used.
    if tok.get("refresh_token"):
        cfg["refresh_token"] = tok["refresh_token"]
        _save_config(cfg)
    return tok["access_token"]


def _spotify_pages(token, url):
    """Every item of a paged Spotify list, following `next`; a 429 waits as long as it is told to."""
    while url:
        try:
            page = json.loads(http(url, headers={"Authorization": f"Bearer {token}"}))
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(min(int(e.headers.get("Retry-After", "5")) + 1, 120))
                continue
            raise
        # A playlist's page is `items`; since February 2026 the list sits one level down as well.
        items = page.get("items")
        if isinstance(items, dict):
            page, items = items, items.get("items")
        for it in items or []:
            yield it
        url = page.get("next")
        time.sleep(0.2)


def _track_of(item):
    """The track of a saved-tracks or playlist item, under whichever name this API version uses."""
    t = item.get("track") or item.get("item") or item
    if not isinstance(t, dict) or t.get("type", "track") != "track" or t.get("is_local"):
        return None
    name = t.get("name")
    artists = ", ".join(a.get("name", "") for a in t.get("artists") or [] if a.get("name"))
    if not name or not artists:
        return None
    return name, artists, (t.get("album") or {}).get("name") or "", round((t.get("duration_ms") or 0) / 1000)


def cmd_spotify(args):
    token = _access_token()
    if token is None:
        print("spotify: not signed in (spotify-login), skipped")
        return
    con = db()
    seen = new = 0
    for item in _spotify_pages(token, f"{SPOTIFY_API}/me/tracks?limit=50"):
        t = _track_of(item)
        if t:
            seen += 1
            new += add_song(con, "liked", *t)
    con.commit()
    print(f"spotify: {seen} liked songs, {new} new")
    lists = read = 0
    for pl in _spotify_pages(token, f"{SPOTIFY_API}/me/playlists?limit=50"):
        lists += 1
        pid = pl.get("id")
        if not pid:
            continue
        n = 0
        try:
            for item in _spotify_pages(token, f"{SPOTIFY_API}/playlists/{pid}/items?limit=50"):
                t = _track_of(item)
                if t:
                    n += 1
                    new += add_song(con, "playlist", *t)
        except urllib.error.HTTPError as e:
            # Someone else's playlist: Spotify gives a development-mode app its name and nothing in it.
            if e.code not in (403, 404):
                raise
        read += n > 0
        con.commit()
    print(f"spotify: {lists} playlists, {read} with songs this app may read; {new} songs new in all")


# --------------------------------------------------------------------------------------------------
# lrclib
# --------------------------------------------------------------------------------------------------

def _lrclib(path, query):
    try:
        return json.loads(http(f"{LRCLIB}/{path}?{urllib.parse.urlencode(query)}", timeout=20))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def _synced(record):
    if not isinstance(record, dict) or record.get("instrumental"):
        return None
    lrc = record.get("syncedLyrics")
    return lrc if isinstance(lrc, str) and lrc.strip() else None


def versions(hits, artist, dur):
    """
    Of search hits, the timed lyrics worth keeping as (seconds, lrc). With the length known, the one
    closest within MATCH_SECONDS. With it unknown (a chart entry), up to MAX_VERSIONS by the same
    artist whose lengths differ by more than MATCH_SECONDS, in lrclib's own order.
    """
    timed = [(float(h.get("duration") or 0), _synced(h), h.get("artistName") or "") for h in hits or []]
    timed = [(d, lrc, a) for d, lrc, a in timed if lrc]
    if dur > 0:
        near = [(abs(d - dur), d, lrc) for d, lrc, _ in timed if abs(d - dur) <= MATCH_SECONDS]
        return [(dur, min(near)[2])] if near else []
    out = []
    for d, lrc, a in timed:
        if d <= 0 or not same_artist(a, artist):
            continue
        if all(abs(d - kept) > MATCH_SECONDS for kept, _ in out):
            out.append((round(d), lrc))
        if len(out) >= MAX_VERSIONS:
            break
    return out


def look_up(title, artist, album, dur):
    """[(seconds, lrc)] for a song, empty when lrclib has no timed lyrics for it."""
    if dur > 0:
        exact = _lrclib("get-cached", {"track_name": title, "artist_name": artist, "album_name": album, "duration": dur})
        lrc = _synced(exact)
        if lrc:
            return [(dur, lrc)]
        time.sleep(PAUSE_S)
    hits = _lrclib("search", {"track_name": title, "artist_name": first_artist(artist)})
    # A length that fits nothing (a history length is a guess) still leaves the versions lrclib has:
    # the car takes one only if it is the length of what it plays.
    return versions(hits, artist, dur) or (versions(hits, artist, 0) if dur > 0 else [])


def cmd_build(args):
    con = db()
    now = int(time.time())
    todo = con.execute("""
        SELECT s.id, s.title, s.artist, s.album, s.dur FROM song s
        WHERE NOT EXISTS (SELECT 1 FROM answer a WHERE a.song = s.id AND (a.lrc IS NOT NULL OR a.checked > ?))
        ORDER BY s.source = 'liked' DESC, s.source = 'playlist' DESC, s.source = 'history' DESC, s.id""",
                       (now - RETRY_NONE_AFTER_S,)).fetchall()
    if args.max_lookups is not None:
        todo = todo[:args.max_lookups]
    print(f"build: {len(todo)} songs to ask lrclib about")
    found = failed = 0
    for n, (sid, title, artist, album, dur) in enumerate(todo, 1):
        try:
            got = look_up(title, artist, album, dur)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ConnectionError) as e:
            failed += 1
            if failed >= 25 and failed > n // 2:
                print(f"build: lrclib is failing ({type(e).__name__}); stopping the lookups here")
                break
            time.sleep(5)
            continue
        con.execute("DELETE FROM answer WHERE song = ?", (sid,))
        if got:
            found += 1
            con.executemany("INSERT INTO answer (song, dur, lrc, checked) VALUES (?,?,?,?)",
                            [(sid, int(d), lrc, now) for d, lrc in got])
        else:
            con.execute("INSERT INTO answer (song, dur, lrc, checked) VALUES (?,0,NULL,?)", (sid, now))
        if args.verbose:
            print(f"  {'+' if got else '-'} {title} / {artist}")
        if n % 100 == 0:
            con.commit()
            print(f"build: {n} of {len(todo)} asked, {found} found")
        time.sleep(PAUSE_S)
    con.commit()
    write_pack(con, args.out)


def write_pack(con, out):
    rows = con.execute("""SELECT s.title, s.artist, a.dur, a.lrc FROM answer a JOIN song s ON s.id = a.song
                          WHERE a.lrc IS NOT NULL ORDER BY s.id, a.dur""").fetchall()
    # The same words for the same song and length, reached from two lists, go in once.
    seen, unique = set(), []
    for t, a, d, lrc in rows:
        mark = (base_title(t), d, hashlib.sha256(lrc.encode("utf-8")).digest())
        if mark not in seen:
            seen.add(mark)
            unique.append((t, a, d, lrc))
    rows = unique
    body = "".join(json.dumps({"t": t, "a": a, "d": d, "lrc": lrc}, ensure_ascii=False, separators=(",", ":")) + "\n"
                   for t, a, d, lrc in rows)
    pack_id = hashlib.sha256(body.encode("utf-8")).hexdigest()[:16]
    songs = con.execute("SELECT count(DISTINCT song) FROM answer WHERE lrc IS NOT NULL").fetchone()[0]
    head = json.dumps({"v": 1, "id": pack_id, "built": int(time.time() * 1000), "songs": songs, "versions": len(rows)}) + "\n"
    os.makedirs(os.path.dirname(out), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(out), prefix=".pack-")
    with os.fdopen(fd, "wb") as raw:
        # mtime 0: the same songs give the same bytes, so an unchanged package keeps its id and size.
        with gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0) as gz:
            gz.write((head + body).encode("utf-8"))
    os.chmod(tmp, 0o644)
    os.replace(tmp, out)
    with open(out + ".id", "w", encoding="utf-8") as f:
        f.write(pack_id + "\n")
    total = con.execute("SELECT count(*) FROM song").fetchone()[0]
    print(f"package {pack_id}: {songs} of {total} songs have timed lyrics ({len(rows)} versions), "
          f"{os.path.getsize(out) / 1e6:.1f} MB at {out}")


def cmd_status(args):
    con = db()
    for source, n, have in con.execute("""
            SELECT CASE WHEN source LIKE 'chart%' THEN 'charts' ELSE source END AS src, count(*),
                   sum(EXISTS (SELECT 1 FROM answer a WHERE a.song = song.id AND a.lrc IS NOT NULL))
            FROM song GROUP BY src ORDER BY src"""):
        print(f"{source}: {n} songs, {have} with timed lyrics")
    asked = con.execute("SELECT count(DISTINCT song) FROM answer").fetchone()[0]
    print(f"asked about {asked} of {con.execute('SELECT count(*) FROM song').fetchone()[0]}; "
          f"spotify {'signed in' if _load_config().get('refresh_token') else 'not signed in'}")
    if os.path.isfile(args.out):
        print(f"package: {os.path.getsize(args.out) / 1e6:.1f} MB, written {time.strftime('%Y-%m-%d %H:%M', time.localtime(os.path.getmtime(args.out)))}")


def cmd_weekly(args):
    args.again = False
    cmd_charts(args)
    try:
        cmd_spotify(args)
    except (urllib.error.URLError, KeyError, json.JSONDecodeError) as e:
        print(f"spotify: not read this time ({type(e).__name__}); the package keeps what it had")
    cmd_build(args)


def main():
    ap = argparse.ArgumentParser(description="Build the car's lyrics package.")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--max-lookups", type=int, default=None, help="ask lrclib about at most this many songs")
    ap.add_argument("--verbose", action="store_true", help="name each song as it is looked up")
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("charts"); c.add_argument("--again", action="store_true", help="read every year again")
    c = sub.add_parser("history"); c.add_argument("paths", nargs="+")
    c = sub.add_parser("spotify-login"); c.add_argument("client_id"); c.add_argument("--redirect", default=DEFAULT_REDIRECT)
    c = sub.add_parser("spotify-code"); c.add_argument("address")
    sub.add_parser("spotify"); sub.add_parser("build"); sub.add_parser("weekly"); sub.add_parser("status")
    args = ap.parse_args()
    {"charts": cmd_charts, "history": cmd_history, "spotify-login": cmd_spotify_login, "spotify-code": cmd_spotify_code, "spotify": cmd_spotify,
     "build": cmd_build, "weekly": cmd_weekly, "status": cmd_status}[args.cmd](args)


if __name__ == "__main__":
    main()
