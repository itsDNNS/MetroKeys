"""Case decision of 05b-dictionaries §3.4, shared by the German and the Spanish build (§8.3)."""


def case_kind_rank(form):
    """capitalised before mixed case before all caps"""
    if len(form) > 1 and form == form.upper():
        return 2
    if form[0].isupper() and form[1:] == form[1:].lower():
        return 0
    return 1


def decide(key, variants, dict_forms):
    """Returns [(form, share), ...] or None (no case information).

    variants: running-text counts of the key's cased variants that occur at least twice;
    dict_forms: the language's dictionary forms of the key (German forms / Spanish forms).
    """
    evidence = dict(variants)
    for f in dict_forms:
        evidence.setdefault(f, 0)
    total = sum(evidence.values())
    if total >= 3:
        ranked = sorted(evidence.items(), key=lambda kv: (-kv[1], kv[0]))
        chosen = []
        for i, (form, c) in enumerate(ranked):
            share = c / total
            if form == key or form in dict_forms:
                if i == 0 or share >= 0.1:
                    chosen.append([form, share])
            elif i == 0 and share >= 0.5:
                chosen.append([form, share])
        lower_idx = next((i for i, (f, _) in enumerate(chosen) if f == key), None)
        if lower_idx is not None and lower_idx > 0 and chosen[0][1] < 0.75:
            # swap the forms, the shares stay in place (lower case takes the first share)
            chosen[0][0], chosen[lower_idx][0] = chosen[lower_idx][0], chosen[0][0]
        return [(f, s) for f, s in chosen] if chosen else None
    if dict_forms:
        if key in dict_forms:
            return [(key, 0.75)] + [(f, 0.25) for f in sorted(dict_forms)
                                    if f != key and f[0].isupper() and case_kind_rank(f) == 0]
        best = sorted(dict_forms, key=lambda f: (case_kind_rank(f), f))[0]
        return [(best, 1.0)]
    return None
