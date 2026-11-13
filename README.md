# Campus Mystery — JavaFX + SQLite

A compact desktop lost-and-found system for SRM University-AP. It is written in Java 21 with JavaFX, JDBC, SQLite, and a small CSS stylesheet. The Java source is intentionally kept near the requested **1,000–1,500 LOC** range.

## Features

- SmartMatch ranks lost/found candidates using type (30%), colour (15%), location (20%), date (15%), and public-description similarity (20%). It never decides ownership.
- Lost and found reports can optionally include a local image, and every signed-in user can browse the found-item register.
- TrustGuard supports one to ten private questions per found item (three by default). Answers are stored as hashes, never displayed, and claimant responses are not stored in plaintext.
- Each claimant receives the same questions and has at most three attempts. Their individual percentage is recorded for administrator review. Students can review all submitted lost reports and claims in **My submissions**.
- The administrator can request more evidence, manually approve or reject a qualifying claim, add an audit note, and confirm physical handover before an item becomes `RETURNED`.
- SQLite is created automatically at `data/campus_mystery.db`; no database server or configuration is needed.

## Run

```bash
mvn javafx:run
```

Demo users:

- Administrator: `admin@campus.edu` / `password123`
- Student: `alex@campus.edu` / `password123`

## Demonstration flow

1. Sign in as the administrator and register a found item with three hidden TrustGuard questions (the default; choose 1–10 as needed).
2. Sign in as a student, report a lost item (with an optional photo), browse **Found items**, run SmartMatch, and create a claim.
3. In **My submissions**, answer the confidential questions. A score of 60% or more goes to `UNDER_REVIEW`; otherwise the student may retry until three attempts are used.
4. Sign in as administrator. In **Admin review**, compare ranked candidates, add a note, approve the best qualifying claim, then confirm physical handover. Only then does the item change to `RETURNED`.

## Project layout

```text
src/main/java/com/campusmystery/
  CampusMysteryApp.java  JavaFX screens and workflows
  Database.java          SQLite schema, JDBC operations, security controls
  SmartMatch.java        weighted, explainable candidate ranking
  Models.java            small immutable data objects
src/main/resources/campus.css
```
