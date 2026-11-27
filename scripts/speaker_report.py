#!/usr/bin/env python3
"""Turn a "Speaker report" issue into a proposed entry in data/speakers.json.

Run by .github/workflows/speaker-report.yml, which then opens a pull request for a maintainer
to review. The issue text is written by anyone, so it is only ever read as data here: it is
cut into fields, each field is checked against a fixed set of rules (the same ones the app
applies when it reads the list), and the file is written back by a JSON serializer. Nothing
from the issue is run, interpolated into a command, or copied unchecked, and the log field is
never stored.

    ISSUE_BODY, ISSUE_NUMBER, GITHUB_REPOSITORY   from the workflow's environment
    python3 scripts/speaker_report.py data/speakers.json comment.md
"""
import datetime
import json
import os
import re
import sys

PLATFORMS = {
    "AirPlay 2": "AirPlay2",
    "AirPlay 1": "AirPlay",
    "DLNA": "DLNA",
    "Google Cast": "Google Cast",
    "Snapcast": "Snapcast",
    "Sendspin": "Sendspin",
    "AriaCast": "AriaCast",
    "VBAN": "VBAN",
    "No local protocol": None,
    "Other": None,
}
VERDICTS = {
    "Works": "good",
    "Works with a catch": "partial",
    "Does not work": "bad",
    "Not supported (AriaCast cannot find or reach it)": "unsupported",
}
ID_PATTERN = re.compile(r"[a-z0-9][a-z0-9-]{2,63}")
CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")
MAX_ENTRIES = 500


def parse_form(body):
    """Cut a rendered issue form into {label: text}. GitHub writes each field as `### Label`."""
    fields = {}
    label = None
    lines = []
    for line in (body or "").replace("\r\n", "\n").split("\n"):
        if line.startswith("### "):
            if label is not None:
                fields[label] = "\n".join(lines).strip()
            label, lines = line[4:].strip(), []
        elif label is not None:
            lines.append(line)
    if label is not None:
        fields[label] = "\n".join(lines).strip()
    return {k: ("" if v == "_No response_" else v) for k, v in fields.items()}


def clean(text, limit):
    """One line of plain text: control characters out, whitespace collapsed, cut to `limit`."""
    text = CONTROL.sub("", text or "")
    return re.sub(r"\s+", " ", text).strip()[:limit]


def slug(text):
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")[:60]


def mention_for(fields, speaker):
    """The phrase a speaker is recognised by: the model the app reported, else what was typed."""
    found = re.search(r"model=(.*?)(?:, (?:name|manufacturer|protocol)=|$)", fields.get("Speaker details", ""), re.M)
    phrase = clean(found.group(1) if found else speaker, 40).lower()
    # At least three letters or digits: a name of punctuation would recognise nothing.
    return phrase if len(re.sub(r"[^a-z0-9]", "", phrase)) >= 3 else None


def build_entry(fields, number, repo, doc):
    """Returns (entry, None) or (None, why not)."""
    speaker = clean(fields.get("Speaker", ""), 80)
    protocol = clean(fields.get("Protocol", ""), 40)
    verdict = VERDICTS.get(clean(fields.get("How does it work?", ""), 80))
    if not speaker:
        return None, "The *Speaker* field is empty."
    if protocol not in PLATFORMS:
        return None, "The *Protocol* is not one of the options in the form."
    if verdict is None:
        return None, "*How does it work?* is not one of the options in the form."
    symptoms = [s for s in (clean(l, 240) for l in fields.get("What happens", "").split("\n")) if s][:8]
    if verdict != "good" and not symptoms:
        return None, "Please describe what happens in *What happens*: an entry that does not simply work needs its symptoms."
    mention = mention_for(fields, speaker)
    if mention is None:
        return None, "The speaker's name is too short to recognise it by."
    platform = PLATFORMS[protocol]
    for existing in doc["speakers"]:
        if mention in existing["match"].get("mentions", []) and existing["match"].get("platform") == platform:
            return None, "This speaker is already listed as `%s`; a maintainer can add your report as evidence there." % existing["id"]
    if len(doc["speakers"]) >= MAX_ENTRIES:
        return None, "The list is full."

    base = slug("%s %s" % (speaker, protocol)) or "speaker"
    taken = {s["id"] for s in doc["speakers"]}
    entry_id, n = base, 2
    while entry_id in taken or not ID_PATTERN.fullmatch(entry_id):
        entry_id = "%s-%d" % (base, n)
        n += 1
        if n > 99:
            return None, "Could not make an id for this speaker."

    entry = {
        "id": entry_id,
        "speaker": speaker,
        "protocol": protocol,
        "verdict": verdict,
        "match": {"platform": platform, "mentions": [mention]} if platform else {"mentions": [mention]},
    }
    if symptoms:
        entry["symptoms"] = symptoms
    entry["evidence"] = "[#%d](https://github.com/%s/issues/%d), unverified report" % (number, repo, number)
    return entry, None


def main(argv, env):
    data_path, comment_path = argv[1], argv[2]
    number = int(env["ISSUE_NUMBER"])
    repo = env["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9._-]+/[A-Za-z0-9._-]+", repo):
        raise SystemExit("unexpected repository name")
    with open(data_path, encoding="utf-8") as f:
        doc = json.load(f)
    entry, why = build_entry(parse_form(env.get("ISSUE_BODY", "")), number, repo, doc)
    if entry is None:
        comment = "Thanks for the report. I could not turn it into a list entry: %s\n\nEdit the issue and I will try again." % why
        changed = False
    else:
        doc["speakers"].append(entry)
        doc["version"] = int(doc["version"]) + 1
        # SPEAKER_REPORT_TODAY only exists so the tests can pin the date.
        doc["updated"] = env.get("SPEAKER_REPORT_TODAY") or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
        with open(data_path, "w", encoding="utf-8") as f:
            f.write(json.dumps(doc, indent=2, ensure_ascii=False) + "\n")
        comment = (
            "Thanks. I proposed this entry in a pull request for a maintainer to review (verdict: **%s**, "
            "protocol: **%s**). Your log stays in this issue and is not copied into the list." % (entry["verdict"], entry["protocol"])
        )
        changed = True
    with open(comment_path, "w", encoding="utf-8") as f:
        f.write(comment + "\n")
    out = env.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write("changed=%s\n" % ("true" if changed else "false"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv, os.environ))
