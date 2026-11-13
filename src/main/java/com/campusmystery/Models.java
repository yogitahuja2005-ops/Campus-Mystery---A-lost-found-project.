package com.campusmystery;

import java.time.LocalDate;

/** Small immutable data objects used by the UI and SQLite layer. */
public final class Models {
    private Models() { }

    public record User(int id, String name, String email, String role) {
        public boolean isAdmin() { return "ADMIN".equals(role); }
        @Override public String toString() { return name + " (" + email + ")"; }
    }

    public record LostItem(int id, int userId, String name, String category, String colour,
                           String location, LocalDate date, String description, String photoPath, String status) {
        @Override public String toString() { return "#" + id + "  " + name + " — " + status; }
    }

    public record FoundItem(int id, String name, String category, String colour, String location,
                            LocalDate date, String description, String photoPath, String status) {
        @Override public String toString() { return "#" + id + "  " + name + " — " + status; }
    }

    public record Match(FoundItem item, double score, String explanation) { }

    public record Question(int id, String text) { }

    public record Claim(int id, int foundId, String itemName, String claimant, int claimantId,
                        String status, int attempts, double score, String note) { }
}
