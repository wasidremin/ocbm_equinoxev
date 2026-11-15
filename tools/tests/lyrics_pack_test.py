#!/usr/bin/env python3
"""Tests for host/gm_ccpa/tools/lyrics_pack.py: the parts that need no network.

    python3 tools/tests/lyrics_pack_test.py
"""
import gzip
import importlib.util
import json
import os
import sqlite3
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("lyrics_pack", os.path.join(HERE, "../../host/gm_ccpa/tools/lyrics_pack.py"))
lp = importlib.util.module_from_spec(spec)
sys.dont_write_bytecode = True
spec.loader.exec_module(lp)


class Naming(unittest.TestCase):
    """The same answers as `LyricsPackTest.aSongIsNamedAsTheBuilderNamesIt` in the app: the two must agree."""

    def test_titles(self):
        for title, base in [
            ("1979 - Remastered 2012", "1979"),
            ("Brimful Of Asha - Norman Cook Remix Single Version", "brimful of asha"),
            ("(I Can't Get No) Satisfaction", "i can t get no satisfaction"),
            ("Yoü and I", "you and i"),
            ("Music Sounds Better With You - Radio Edit", "music sounds better with you"),
            ("Señorita (feat. X)", "senorita"),
            ("Don't Stop Believin'", "don t stop believin"),
            ("東京 - Live", "東京"),
            ("P!nk [Remix]", "p nk"),
        ]:
            self.assertEqual(base, lp.base_title(title), title)

    def test_artists(self):
        self.assertEqual({"daryl", "hall", "john", "oates"}, lp.artist_tokens("Daryl Hall & John Oates"))
        self.assertEqual({"beyonce", "jay"}, lp.artist_tokens("Beyoncé featuring Jay-Z"))
        self.assertEqual(set(), lp.artist_tokens("The The"))
        self.assertTrue(lp.same_artist("Hall & Oates", "Daryl Hall & John Oates"))
        self.assertTrue(lp.same_artist("Queen", "Queen & David Bowie"))
        self.assertFalse(lp.same_artist("Blondie", "Queen"))
        self.assertTrue(lp.same_artist("The The", "The The"))
        self.assertFalse(lp.same_artist("U2", "Queen"))
        self.assertEqual("Stardust", lp.first_artist("Stardust, Benjamin Diamond, Alan Braxe"))
        self.assertEqual("Beyoncé", lp.first_artist("Beyoncé featuring Jay-Z"))


class Charts(unittest.TestCase):
    def test_the_list_is_the_widest_numbered_table(self):
        page = """<table class="wikitable"><tr><th>x</th></tr><tr><td>note</td></tr></table>
        <table class="wikitable sortable"><tr><th>No.</th><th>Title</th><th>Artist(s)</th></tr>
        <tr><td>1</td><td>"<a>Call Me</a>"</td><td><a>Blondie</a></td></tr>
        <tr><td>2</td><td>"<a>Another Brick in the Wall, Part II</a>"<sup>[1]</sup></td><td>Pink Floyd</td></tr>
        <tr><td>3</td><td>"<a>First</a>" / "<a>Second</a>"</td><td>Two Sided</td></tr></table>"""
        self.assertEqual([("Call Me", "Blondie"), ("Another Brick in the Wall, Part II", "Pink Floyd"), ("First", "Two Sided")],
                         lp.chart_rows(page))


class History(unittest.TestCase):
    def play(self, title, artist, ms, end="trackdone", album="Album"):
        return {"master_metadata_track_name": title, "master_metadata_album_artist_name": artist,
                "master_metadata_album_album_name": album, "ms_played": ms, "reason_end": end}

    def test_a_song_counts_once_played_and_its_length_is_its_commonest_full_play(self):
        songs = lp.history_songs([
            self.play("A", "Act", 200_400), self.play("A", "Act", 200_100), self.play("A", "Act", 187_000),
            self.play("A", "Act", 12_000, "fwdbtn"),
            self.play("B", "Act", 90_000, "fwdbtn"),          # never played through: no length
            self.play("C", "Act", 5_000, "fwdbtn"),           # skipped at once: not a song they play
            {"episode_name": "a podcast", "ms_played": 900_000, "reason_end": "trackdone"},
        ])
        self.assertEqual({("A", "Act", "Album", 200), ("B", "Act", "Album", 0)}, set(songs))


class Versions(unittest.TestCase):
    def hit(self, dur, lrc="[00:01.00]x", artist="Blondie", instrumental=False):
        return {"duration": dur, "syncedLyrics": lrc, "artistName": artist, "instrumental": instrumental}

    def test_a_known_length_takes_the_closest_within_three_seconds(self):
        hits = [self.hit(200, "a"), self.hit(213, "b"), self.hit(211.4, "c")]
        self.assertEqual([(212, "c")], lp.versions(hits, "Blondie", 212))
        self.assertEqual([], lp.versions(hits, "Blondie", 300))

    def test_an_unknown_length_keeps_up_to_three_cuts_by_the_same_act(self):
        hits = [self.hit(212, "single"), self.hit(213, "same cut"), self.hit(487, "long"), self.hit(212, "cover", artist="Someone"),
                self.hit(300, None), self.hit(250, "inst", instrumental=True), self.hit(330, "third"), self.hit(400, "fourth")]
        self.assertEqual([(212, "single"), (487, "long"), (330, "third")], lp.versions(hits, "Blondie", 0))


class Package(unittest.TestCase):
    def test_the_package_is_gzip_ndjson_with_a_head_and_no_song_twice(self):
        with tempfile.TemporaryDirectory() as d:
            con = sqlite3.connect(":memory:")
            lp.STATE_DIR = d
            con = lp.db()
            lp.add_song(con, "chart1980", "Call Me", "Blondie")
            lp.add_song(con, "history", "Call Me - Remastered", "Blondie", "Hits", 212)
            lp.add_song(con, "history", "No Lyrics", "Nobody", "", 100)
            con.execute("INSERT INTO answer (song, dur, lrc, checked) VALUES (1,212,'words',1),(1,487,'long',1),(2,212,'words',1),(3,0,NULL,1)")
            out = os.path.join(d, "out", "pack.ndjson.gz")
            lp.write_pack(con, out)
            lines = [json.loads(l) for l in gzip.open(out, "rt", encoding="utf-8")]
            head, songs = lines[0], lines[1:]
            self.assertEqual(1, head["v"])
            self.assertEqual(2, head["songs"])
            self.assertEqual(2, head["versions"])
            self.assertEqual([("Call Me", 212), ("Call Me", 487)], [(s["t"], s["d"]) for s in songs])
            self.assertEqual(head["id"], open(out + ".id").read().strip())
            # The same songs again: the same bytes but for the build time, and the same id.
            first = head["id"]
            lp.write_pack(con, out)
            self.assertEqual(first, json.loads(gzip.open(out, "rt", encoding="utf-8").readline())["id"])


if __name__ == "__main__":
    unittest.main(verbosity=1)
