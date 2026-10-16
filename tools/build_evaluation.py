#!/usr/bin/env python3
"""Test sets of the dictionary evaluation (spec/05c-dictionary-evaluation.md).

Usage (from the repository root):
    python3 tools/build_evaluation.py download            # fetch the real-error lists (05c §2.3), verify hashes
    python3 tools/build_evaluation.py generate [TAG ...]  # write evaluation/testsets/ and evaluation/lexicon/

The generator needs the pinned sources of tools/sources.json (python3 tools/build_dictionaries.py download) and the
Wiktionary pass caches of the dictionary build; it runs the build pipeline in dry mode (nothing is written there) and
never reads a built word list. Its output is committed and reviewed like code; the evaluation itself (Gradle tasks of
the engine module, docs/EVALUATION.md) needs neither the downloads nor network access.
"""

import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "dicteval"))
sys.path.insert(0, os.path.join(HERE, "dictbuild"))
sys.path.insert(0, HERE)

import build_dictionaries as bd  # noqa: E402
import common  # noqa: E402

ROOT = os.path.dirname(HERE)
TESTSETS = os.path.join(ROOT, "evaluation", "testsets")
LEXICON = os.path.join(ROOT, "evaluation", "lexicon")
TAGS = ["de-DE", "en-US", "es-ES", "fr-FR", "it-IT", "pt-BR"]
SALTS = ["A", "B"]
CODE = "wordfreq/large_{}.msgpack.gz"


def manifest():
    with open(os.path.join(HERE, "dicteval", "sources.json"), encoding="utf-8") as f:
        return json.load(f)["sources"]


def download():
    for s in manifest():
        path = os.path.join(bd.SRC, s["path"])
        if not os.path.exists(path):
            os.makedirs(os.path.dirname(path), exist_ok=True)
            print("downloading", s["url"])
            req = urllib.request.Request(s["url"], headers={"User-Agent": bd.USER_AGENT})
            with urllib.request.urlopen(req) as resp:
                data = resp.read()
            with open(path + ".part", "wb") as out:
                out.write(data)
            os.replace(path + ".part", path)
    verify()


def verify():
    import hashlib
    bad = []
    for s in manifest():
        path = os.path.join(bd.SRC, s["path"])
        if not os.path.exists(path):
            bad.append(f"missing: {s['path']}")
            continue
        with open(path, encoding="utf-8") as f:
            content = json.load(f)["query"]["pages"][0]["revisions"][0]["slots"]["main"]["content"]
        if hashlib.sha256(content.encode("utf-8")).hexdigest() != s["contentSha256"]:
            bad.append(f"content hash mismatch: {s['path']}")
    if bad:
        sys.exit("real-error list check failed:\n  " + "\n  ".join(bad))
    print("all real-error list hashes verified")


def input_hashes(tag):
    """The pinned inputs of a language's test sets (paths below build-data/src with their manifest SHA-256), the
    layout file, the real-error list and the generator sources."""
    lang = {"de-DE": "de", "en-US": "en", "es-ES": "es", "pt-BR": "pt", "it-IT": "it", "fr-FR": "fr"}[tag]
    wanted = []
    for s in bd.manifest():
        p = s["path"]
        if p.startswith("cv/"):
            if p.startswith(f"cv/{lang}/") or p == "cv/LICENSE":
                wanted.append((p, s["sha256"]))
        elif p.startswith("fw/"):
            if os.path.basename(p).startswith(("pt_br" if lang == "pt" else lang) + "_"):
                wanted.append((p, s["sha256"]))
        elif p.startswith("tatoeba/"):
            code = {"de": "deu", "en": "eng", "es": "spa", "pt": "por", "it": "ita", "fr": "fra"}[lang]
            if os.path.basename(p).startswith(code):
                wanted.append((p, s["sha256"]))
        elif p.startswith("wiktionary/"):
            name = os.path.basename(p)
            if name.startswith(f"{lang}wiktionary") or name.startswith("enwiktionary") and lang != "de":
                wanted.append((p, s["sha256"]))
        elif p.startswith("wordfreq/"):
            if p == CODE.format(lang):
                wanted.append((p, s["sha256"]))
        elif p.startswith("hunspell/"):
            d = p.split("/")[1]
            if d == {"de": "de", "es": "es", "pt": "pt_BR", "it": "it_IT", "fr": "fr"}.get(lang):
                wanted.append((p, s["sha256"]))
        elif p.startswith("scowl/") and lang == "en":
            wanted.append((p, s["sha256"]))
    for s in manifest():
        if s["language"] == tag:
            wanted.append((s["path"] + "#content", s["contentSha256"]))
    layout = os.path.join(ROOT, "spec", "data", f"layouts-{lang}.json")
    wanted.append((f"spec/data/layouts-{lang}.json", common.sha256_file(layout)))
    for fn in sorted(os.listdir(os.path.join(HERE, "dicteval"))):
        if fn.endswith(".py"):
            wanted.append((f"tools/dicteval/{fn}", common.sha256_file(os.path.join(HERE, "dicteval", fn))))
    return dict(wanted)


def generate(tags):
    import generate as gen
    bd.verify()
    verify()
    gen.run(tags or TAGS, SALTS, TESTSETS, LEXICON, input_hashes)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "generate"
    if cmd == "download":
        download()
    elif cmd == "verify":
        verify()
    elif cmd == "generate":
        generate(sys.argv[2:])
    else:
        sys.exit(__doc__)
