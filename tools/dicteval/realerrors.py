"""Parsers of the real-error lists of spec/05c-dictionary-evaluation.md §2.3 (Wikipedia pages pinned by revision id).

Each parser yields (misspelling, correction) pairs exactly as the page writes them; the generator keeps an entry only
if its correction is a single trusted word and its misspelling is no form of the language. The parsing rule of each page
is recorded in RULES (and in the generation report).
"""

import re

RULES = {
    "en-US": "lines ' misspelling->correction'; an entry with several corrections (comma) is skipped",
    "es-ES": "lines ' term|parameter|suggestions' (each starting with a space); suggestions split at top-level commas, "
             "a parenthesised explanation removed; an entry with several suggestions is skipped",
    "fr-FR": "templates {{Suggestion|wrong|right}} without complexe=oui (positional arguments 1 and 2; named "
             "arguments ignored)",
    "pt-BR": "lines ' wrong->right'",
    "de-DE": "list lines '* [[wrong]], [[wrong2]] > [[right]] …' of the first three sections (the section 'Korrekte "
             "Schreibweisen mit Verwechslungsgefahr' lists correct spellings and is not used); every wrong link with "
             "the single right link; an entry with several right links is skipped",
}

LINK = re.compile(r"\[\[([^\]|#]+)(?:#[^\]|]*)?(?:\|([^\]]*))?\]\]")


def _top_level_split(text, sep=","):
    out, depth, cur = [], 0, []
    for ch in text:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        if ch == sep and depth == 0:
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    out.append("".join(cur))
    return [p.strip() for p in out if p.strip()]


def parse_en(text):
    for line in text.split("\n"):
        m = re.match(r"^\s*([^\s<>]+)->(.+?)\s*$", line)
        if not m:
            continue
        wrong, right = m.group(1), m.group(2)
        if "," in right:
            continue
        yield wrong, right.strip()


def parse_pt(text):
    for line in text.split("\n"):
        m = re.match(r"^ (.+?)->(.+?)\s*$", line)
        if m:
            yield m.group(1).strip(), m.group(2).strip()


def parse_es(text):
    for line in text.split("\n"):
        if not line.startswith(" "):
            continue
        parts = line.strip().split("|")
        if len(parts) != 3:
            continue
        wrong, _, sugg = parts
        alts = _top_level_split(sugg)
        if len(alts) != 1:
            continue
        right = re.sub(r"\s*\([^)]*\)", "", alts[0]).strip()
        yield wrong.strip(), right


def parse_fr(text):
    for m in re.finditer(r"\{\{\s*Suggestion\s*\|((?:[^{}]|\{\{[^{}]*\}\})*)\}\}", text):
        args = m.group(1).split("|")
        if any(a.strip().startswith("complexe=") for a in args):
            continue
        pos = [a.strip() for a in args if "=" not in a]
        if len(pos) < 2:
            continue
        yield pos[0], pos[1]


def parse_de(text):
    section = 0
    for line in text.split("\n"):
        h = re.match(r"^==\s*([^=].*?)\s*==\s*$", line)
        if h:
            name = h.group(1)
            if name.startswith("Besonders häufige") or name.startswith("Weitere Fälle") or name.startswith("Seltenere"):
                section = 1
            else:
                section = 0
            continue
        if not section or not line.startswith("*") or " > " not in line:
            continue
        left, _, right = line[1:].partition(" > ")
        right_links = []
        # the right part ends where the explanation in parentheses starts
        rhead = right.split(" (")[0]
        for lm in LINK.finditer(rhead):
            right_links.append((lm.group(2) or lm.group(1)).strip())
        if len(right_links) != 1 or "/" in rhead.replace(LINK.sub("", rhead), ""):
            if len(right_links) != 1:
                continue
        if re.search(r"\]\]\s*/\s*\[\[", rhead):
            continue
        for lm in LINK.finditer(left):
            yield (lm.group(2) or lm.group(1)).strip(), right_links[0]


PARSERS = {"en-US": parse_en, "es-ES": parse_es, "fr-FR": parse_fr, "pt-BR": parse_pt, "de-DE": parse_de}
