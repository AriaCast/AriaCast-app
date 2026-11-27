import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import speaker_report as sr  # noqa: E402

HERE = os.path.dirname(__file__)
DATA = os.path.join(HERE, "testdata")


def read(name):
    with open(os.path.join(DATA, name), encoding="utf-8") as f:
        return f.read()


def form(speaker="Acme Play 3", protocol="DLNA", verdict="Works with a catch", happens="It stops", details="", log=""):
    return (
        "### Speaker\n\n%s\n\n### Protocol\n\n%s\n\n### How does it work?\n\n%s\n\n### What happens\n\n%s\n\n"
        "### Speaker details\n\n%s\n\n### Log\n\n%s\n\n### AriaCast version\n\n2.0.0\n"
    ) % (speaker, protocol, verdict, happens, details, log)


class Run:
    """Runs the script the way the workflow does, on a copy of the base list."""

    def __init__(self, body, number="77", repo="AriaCast/AriaCast-app", base="base.json"):
        self.dir = tempfile.mkdtemp()
        self.data = os.path.join(self.dir, "speakers.json")
        self.comment = os.path.join(self.dir, "comment.md")
        self.output = os.path.join(self.dir, "output")
        shutil.copy(os.path.join(DATA, base), self.data)
        self.before = read(base)
        env = {"ISSUE_BODY": body, "ISSUE_NUMBER": number, "GITHUB_REPOSITORY": repo,
               "GITHUB_OUTPUT": self.output, "SPEAKER_REPORT_TODAY": "2026-11-27"}
        sr.main(["x", self.data, self.comment], env)

    def doc(self):
        with open(self.data, encoding="utf-8") as f:
            return json.load(f)

    def text(self):
        with open(self.data, encoding="utf-8") as f:
            return f.read()

    def said(self):
        with open(self.comment, encoding="utf-8") as f:
            return f.read()

    def changed(self):
        with open(self.output, encoding="utf-8") as f:
            return f.read().strip() == "changed=true"

    def unchanged_file(self):
        return self.text() == self.before


class ParseForm(unittest.TestCase):
    def test_cuts_the_form_into_fields(self):
        fields = sr.parse_form(read("issue-acme.md"))
        self.assertEqual(fields["Speaker"], "Acme Play 3")
        self.assertEqual(fields["How does it work?"], "Works with a catch")
        self.assertIn("Needs a manual play", fields["What happens"])

    def test_an_empty_field_is_empty(self):
        self.assertEqual(sr.parse_form("### Speaker\n\n_No response_\n")["Speaker"], "")

    def test_windows_line_endings(self):
        self.assertEqual(sr.parse_form("### Speaker\r\n\r\nX\r\n")["Speaker"], "X")

    def test_nothing_to_parse(self):
        self.assertEqual(sr.parse_form(""), {})
        self.assertEqual(sr.parse_form(None), {})


class Proposal(unittest.TestCase):
    def test_a_report_becomes_an_entry_matching_the_golden_file(self):
        r = Run(read("issue-acme.md"))
        self.assertTrue(r.changed())
        # The app's own parser reads this same file (KnownSpeakersFileTest), so what the bot
        # writes is what the app accepts.
        self.assertEqual(r.text(), read("generated-sample.json"))

    def test_the_entry_and_the_version(self):
        doc = Run(read("issue-acme.md")).doc()
        self.assertEqual(doc["version"], 5)
        self.assertEqual(doc["updated"], "2026-11-27")
        entry = doc["speakers"][-1]
        self.assertEqual(entry["id"], "acme-play-3-dlna")
        self.assertEqual(entry["verdict"], "partial")
        self.assertEqual(entry["match"], {"platform": "DLNA", "mentions": ["play 3 pro"]})
        self.assertEqual(entry["symptoms"], ["Stops after the first track", "Needs a manual play in the Acme app"])
        self.assertEqual(entry["evidence"], "[#77](https://github.com/AriaCast/AriaCast-app/issues/77), unverified report")

    def test_the_log_is_never_stored(self):
        self.assertNotIn("THE-LOG-MARKER", Run(read("issue-acme.md")).text())

    def test_the_existing_entries_are_untouched(self):
        self.assertEqual(Run(read("issue-acme.md")).doc()["speakers"][0], json.loads(read("base.json"))["speakers"][0])

    def test_a_speaker_without_a_protocol_has_no_platform_to_match(self):
        r = Run(form(speaker="Echo Show 5", protocol="No local protocol", verdict="Not supported (AriaCast cannot find or reach it)", happens="Not found"))
        self.assertEqual(r.doc()["speakers"][-1]["match"], {"mentions": ["echo show 5"]})
        self.assertEqual(r.doc()["speakers"][-1]["verdict"], "unsupported")

    def test_a_working_speaker_needs_no_symptoms(self):
        r = Run(form(verdict="Works", happens=""))
        self.assertTrue(r.changed())
        self.assertNotIn("symptoms", r.doc()["speakers"][-1])

    def test_the_comment_says_what_happened(self):
        self.assertIn("pull request", Run(read("issue-acme.md")).said())


class Refusals(unittest.TestCase):
    def assertRefused(self, body, words, **kwargs):
        r = Run(body, **kwargs)
        self.assertFalse(r.changed())
        self.assertTrue(r.unchanged_file(), "a refused report must not touch the list")
        self.assertIn(words, r.said())

    def test_no_speaker(self):
        self.assertRefused(form(speaker=""), "Speaker")

    def test_a_protocol_the_form_does_not_offer(self):
        self.assertRefused(form(protocol="Telepathy"), "Protocol")

    def test_a_verdict_the_form_does_not_offer(self):
        self.assertRefused(form(verdict="Great"), "How does it work")

    def test_something_that_fails_must_say_how(self):
        self.assertRefused(form(verdict="Does not work", happens=""), "What happens")

    def test_a_name_too_short_to_recognise(self):
        self.assertRefused(form(speaker="TV"), "too short")

    def test_a_speaker_that_is_already_listed(self):
        self.assertRefused(form(speaker="WiiM", protocol="DLNA"), "already listed as `wiim-dlna-restart`")

    def test_the_same_name_on_another_protocol_is_a_new_entry(self):
        self.assertTrue(Run(form(speaker="WiiM", protocol="AirPlay 2")).changed())

    def test_an_empty_issue(self):
        self.assertRefused("", "Speaker")


class Hostile(unittest.TestCase):
    def test_quotes_backslashes_and_markup_stay_data(self):
        r = Run(form(speaker='Evil "speaker" \\ <script>', happens='x"}], "verdict": "good", "y": [{"z'))
        doc = r.doc()  # still valid JSON
        self.assertEqual(len(doc["speakers"]), 2)
        self.assertEqual(doc["speakers"][-1]["verdict"], "partial")

    def test_control_characters_are_removed(self):
        r = Run(form(speaker="Acme\x00 Play\x1b[31m 3", happens="a\x07b"))
        entry = r.doc()["speakers"][-1]
        self.assertNotRegex(entry["speaker"], r"[\x00-\x1f]")
        self.assertEqual(entry["symptoms"], ["ab"])

    def test_lengths_are_capped(self):
        r = Run(form(speaker="S" * 500, happens="\n".join("line %d %s" % (i, "y" * 500) for i in range(40))))
        entry = r.doc()["speakers"][-1]
        self.assertLessEqual(len(entry["speaker"]), 80)
        self.assertLessEqual(len(entry["symptoms"]), 8)
        self.assertTrue(all(len(s) <= 240 for s in entry["symptoms"]))
        self.assertLessEqual(len(entry["match"]["mentions"][0]), 40)

    def test_a_heading_inside_a_field_cannot_add_config(self):
        r = Run(form(happens="x\n### config\n\n{\"sendMetadata\": false}"))
        self.assertNotIn("config", r.doc()["speakers"][-1])

    def test_ids_are_always_valid_and_unique(self):
        r = Run(form(speaker="!!! ???"))
        self.assertFalse(r.changed())  # nothing to make an id from
        for name in ("Acme Play 3", "Acme  play 3", "ACME PLAY 3!"):
            self.assertRegex(Run(form(speaker=name)).doc()["speakers"][-1]["id"], r"^[a-z0-9][a-z0-9-]{2,63}$")

    def test_an_odd_repository_name_stops_the_run(self):
        with self.assertRaises(SystemExit):
            Run(read("issue-acme.md"), repo="a/b; rm -rf /")

    def test_a_non_numeric_issue_number_stops_the_run(self):
        with self.assertRaises(ValueError):
            Run(read("issue-acme.md"), number="1; echo")


if __name__ == "__main__":
    unittest.main()
