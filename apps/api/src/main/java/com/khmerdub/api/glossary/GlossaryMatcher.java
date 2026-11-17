package com.khmerdub.api.glossary;

import com.khmerdub.api.glossary.GlossaryViews.Match;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces misrecognised spellings (variants) with the canonical term, scanning left to right. At each position a
 * canonical term is matched first, so a variant that is part of a correct spelling (e.g. 小明 inside 王小明) is left
 * alone; longer variants win over shorter ones. Exact, case-sensitive matching.
 */
final class GlossaryMatcher {

    record Result(String text, List<Match> matches) {
        int replacements() {
            return matches.stream().mapToInt(Match::count).sum();
        }
    }

    private record Variant(String variant, String term) {
    }

    private final List<String> canonicals;
    private final List<Variant> variants;

    GlossaryMatcher(List<GlossaryTerm> terms) {
        canonicals = terms.stream().map(GlossaryTerm::getSourceTerm).distinct()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
        List<Variant> v = new ArrayList<>();
        for (GlossaryTerm t : terms) {
            for (String s : t.getVariants()) {
                v.add(new Variant(s, t.getSourceTerm()));
            }
        }
        v.sort(Comparator.comparingInt((Variant x) -> x.variant().length()).reversed());
        variants = v;
    }

    Result apply(String text) {
        StringBuilder out = new StringBuilder(text.length());
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> termOf = new LinkedHashMap<>();
        int i = 0;
        outer:
        while (i < text.length()) {
            for (String c : canonicals) {
                if (text.startsWith(c, i)) {
                    out.append(c);
                    i += c.length();
                    continue outer;
                }
            }
            for (Variant v : variants) {
                if (text.startsWith(v.variant(), i)) {
                    out.append(v.term());
                    counts.computeIfAbsent(v.variant(), k -> new int[1])[0]++;
                    termOf.put(v.variant(), v.term());
                    i += v.variant().length();
                    continue outer;
                }
            }
            out.append(text.charAt(i));
            i++;
        }
        List<Match> matches = counts.entrySet().stream()
                .map(e -> new Match(e.getKey(), termOf.get(e.getKey()), e.getValue()[0])).toList();
        return new Result(out.toString(), matches);
    }
}
