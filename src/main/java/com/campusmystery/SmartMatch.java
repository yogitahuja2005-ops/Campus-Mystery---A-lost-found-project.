package com.campusmystery;

import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Explainable scoring only: it ranks candidates and never proves ownership. */
public final class SmartMatch {
    private SmartMatch() { }

    public static Models.Match compare(Models.LostItem lost, Models.FoundItem found) {
        double type = equal(lost.category(), found.category()) || contains(lost.name(), found.name()) ? 30 : 0;
        double colour = equal(lost.colour(), found.colour()) ? 15 : 0;
        double location = similarText(lost.location(), found.location()) ? 20 : 0;
        long days = Math.abs(ChronoUnit.DAYS.between(lost.date(), found.date()));
        double date = days == 0 ? 15 : days <= 2 ? 10 : days <= 7 ? 5 : 0;
        double description = overlap(lost.description() + " " + lost.name(),
                found.description() + " " + found.name()) * 20;
        double score = type + colour + location + date + description;
        String reason = "Type " + (int) type + "/30, colour " + (int) colour + "/15, location "
                + (int) location + "/20, date " + (int) date + "/15, description "
                + Math.round(description) + "/20.";
        return new Models.Match(found, Math.round(score * 10.0) / 10.0, reason);
    }

    private static boolean equal(String a, String b) { return clean(a).equals(clean(b)); }
    private static boolean contains(String a, String b) { return clean(a).contains(clean(b)) || clean(b).contains(clean(a)); }
    private static boolean similarText(String a, String b) { return contains(a, b) || overlap(a, b) >= .40; }
    private static String clean(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).trim(); }

    private static double overlap(String first, String second) {
        Set<String> a = words(first), b = words(second);
        if (a.isEmpty() || b.isEmpty()) return 0;
        long shared = a.stream().filter(b::contains).count();
        return (double) shared / Math.max(a.size(), b.size());
    }

    private static Set<String> words(String value) {
        return Arrays.stream(clean(value).split("[^a-z0-9]+"))
                .filter(s -> s.length() > 2).collect(Collectors.toSet());
    }
}
