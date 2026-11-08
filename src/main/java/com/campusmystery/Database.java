package com.campusmystery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** SQLite persistence. All user-provided values are supplied through prepared statements. */
public final class Database {
    private static final String URL = "jdbc:sqlite:data/campus_mystery.db";
    private static final int MAX_ATTEMPTS = 3;

    public record Secret(String question, String answer) { }

    public Database() {
        try {
            Files.createDirectories(Path.of("data"));
            Class.forName("org.sqlite.JDBC");
            createSchema();
            seedDemoData();
        } catch (IOException | ClassNotFoundException | SQLException e) {
            throw new IllegalStateException("Cannot initialise the SQLite database", e);
        }
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(URL);
        try (Statement s = connection.createStatement()) { s.execute("PRAGMA foreign_keys = ON"); }
        return connection;
    }

    private void createSchema() throws SQLException {
        String[] sql = {
            "CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('STUDENT','ADMIN')))",
            "CREATE TABLE IF NOT EXISTS found_items (id INTEGER PRIMARY KEY AUTOINCREMENT, item_name TEXT NOT NULL, category TEXT NOT NULL, colour TEXT NOT NULL, found_location TEXT NOT NULL, found_date TEXT NOT NULL, public_description TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'AVAILABLE', created_by INTEGER NOT NULL, FOREIGN KEY(created_by) REFERENCES users(id))",
            "CREATE TABLE IF NOT EXISTS lost_items (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, item_name TEXT NOT NULL, category TEXT NOT NULL, colour TEXT NOT NULL, lost_location TEXT NOT NULL, lost_date TEXT NOT NULL, description TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'OPEN', FOREIGN KEY(user_id) REFERENCES users(id))",
            "CREATE TABLE IF NOT EXISTS verification_questions (id INTEGER PRIMARY KEY AUTOINCREMENT, found_id INTEGER NOT NULL, question TEXT NOT NULL, answer_hash TEXT NOT NULL, FOREIGN KEY(found_id) REFERENCES found_items(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS claims (id INTEGER PRIMARY KEY AUTOINCREMENT, found_id INTEGER NOT NULL, claimant_id INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'VERIFICATION_REQUIRED', attempt_count INTEGER NOT NULL DEFAULT 0, score REAL NOT NULL DEFAULT 0, admin_note TEXT DEFAULT '', submitted_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(found_id, claimant_id), FOREIGN KEY(found_id) REFERENCES found_items(id), FOREIGN KEY(claimant_id) REFERENCES users(id))",
            "CREATE TABLE IF NOT EXISTS claim_answers (id INTEGER PRIMARY KEY AUTOINCREMENT, claim_id INTEGER NOT NULL, question_id INTEGER NOT NULL, attempt_number INTEGER NOT NULL, is_correct INTEGER NOT NULL, FOREIGN KEY(claim_id) REFERENCES claims(id) ON DELETE CASCADE, FOREIGN KEY(question_id) REFERENCES verification_questions(id))",
            "CREATE TABLE IF NOT EXISTS audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, claim_id INTEGER, actor_id INTEGER NOT NULL, action TEXT NOT NULL, note TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(claim_id) REFERENCES claims(id), FOREIGN KEY(actor_id) REFERENCES users(id))"
        };
        try (Connection c = connection(); Statement s = c.createStatement()) {
            for (String statement : sql) s.execute(statement);
        }
    }

    private void seedDemoData() throws SQLException {
        try (Connection c = connection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM users")) {
            if (rs.next() && rs.getInt(1) > 0) return;
        }
        register("Campus Administrator", "admin@campus.edu", "password123", "ADMIN");
        register("Alex Student", "alex@campus.edu", "password123", "STUDENT");
        Models.User admin = login("admin@campus.edu", "password123");
        addFoundItem(admin.id(), "Blue Water Bottle", "Bottle", "Blue", "Central Library", LocalDate.now().minusDays(1),
                "Metal water bottle found near the library reading desks.", List.of(
                        new Secret("What is written on the bottom?", "AP2511001"),
                        new Secret("What colour is the lid?", "black"),
                        new Secret("Is there a sticker? If yes, describe it.", "small mountain sticker")));
    }

    public Models.User login(String email, String password) throws SQLException {
        String sql = "SELECT id,name,email,password_hash,role FROM users WHERE lower(email)=lower(?)";
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, email.trim());
            try (ResultSet r = p.executeQuery()) {
                if (r.next() && verifyPassword(password, r.getString("password_hash")))
                    return new Models.User(r.getInt("id"), r.getString("name"), r.getString("email"), r.getString("role"));
                return null;
            }
        }
    }

    public void register(String name, String email, String password, String role) throws SQLException {
        String sql = "INSERT INTO users(name,email,password_hash,role) VALUES(?,?,?,?)";
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, name.trim()); p.setString(2, email.trim()); p.setString(3, passwordHash(password)); p.setString(4, role);
            p.executeUpdate();
        }
    }

    public void addFoundItem(int creator, String name, String category, String colour, String location,
                             LocalDate date, String description, List<Secret> secrets) throws SQLException {
        if (secrets.size() < 3) throw new IllegalArgumentException("Enter at least three private verification questions.");
        String itemSql = "INSERT INTO found_items(item_name,category,colour,found_location,found_date,public_description,created_by) VALUES(?,?,?,?,?,?,?)";
        String questionSql = "INSERT INTO verification_questions(found_id,question,answer_hash) VALUES(?,?,?)";
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement item = c.prepareStatement(itemSql, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement question = c.prepareStatement(questionSql)) {
                item.setString(1, name); item.setString(2, category); item.setString(3, colour); item.setString(4, location);
                item.setString(5, date.toString()); item.setString(6, description); item.setInt(7, creator); item.executeUpdate();
                try (ResultSet keys = item.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("No item id returned");
                    int foundId = keys.getInt(1);
                    for (Secret secret : secrets) {
                        question.setInt(1, foundId); question.setString(2, secret.question().trim());
                        question.setString(3, answerHash(secret.answer())); question.addBatch();
                    }
                    question.executeBatch();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }

    public void addLostItem(int userId, String name, String category, String colour, String location,
                            LocalDate date, String description) throws SQLException {
        String sql = "INSERT INTO lost_items(user_id,item_name,category,colour,lost_location,lost_date,description) VALUES(?,?,?,?,?,?,?)";
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setInt(1, userId); p.setString(2, name); p.setString(3, category); p.setString(4, colour);
            p.setString(5, location); p.setString(6, date.toString()); p.setString(7, description); p.executeUpdate();
        }
    }

    public List<Models.LostItem> lostItemsFor(int userId) throws SQLException {
        String sql = "SELECT * FROM lost_items WHERE user_id=? AND status='OPEN' ORDER BY id DESC";
        List<Models.LostItem> result = new ArrayList<>();
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setInt(1, userId); try (ResultSet r = p.executeQuery()) { while (r.next()) result.add(lost(r)); }
        }
        return result;
    }

    public List<Models.FoundItem> availableFoundItems() throws SQLException {
        String sql = "SELECT * FROM found_items WHERE status <> 'RETURNED' ORDER BY found_date DESC, id DESC";
        List<Models.FoundItem> result = new ArrayList<>();
        try (Connection c = connection(); Statement p = c.createStatement(); ResultSet r = p.executeQuery(sql)) {
            while (r.next()) result.add(found(r));
        }
        return result;
    }

    public int createClaim(int foundId, int claimantId) throws SQLException {
        String sql = "INSERT INTO claims(found_id,claimant_id) VALUES(?,?)";
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            p.setInt(1, foundId); p.setInt(2, claimantId); p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) { if (r.next()) return r.getInt(1); }
        }
        throw new SQLException("Claim was not created");
    }

    public List<Models.Question> questionsForClaim(int claimId, int claimantId) throws SQLException {
        String allowed = "SELECT attempt_count,status FROM claims WHERE id=? AND claimant_id=?";
        String questions = "SELECT q.id,q.question FROM verification_questions q JOIN claims c ON c.found_id=q.found_id WHERE c.id=? ORDER BY q.id";
        try (Connection c = connection(); PreparedStatement check = c.prepareStatement(allowed)) {
            check.setInt(1, claimId); check.setInt(2, claimantId);
            try (ResultSet r = check.executeQuery()) {
                if (!r.next()) throw new SecurityException("This claim does not belong to the signed-in student.");
                if (r.getInt(1) >= MAX_ATTEMPTS || !"VERIFICATION_REQUIRED".equals(r.getString(2)))
                    throw new IllegalStateException("No verification attempt is currently available.");
            }
            List<Models.Question> result = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement(questions)) {
                p.setInt(1, claimId); try (ResultSet r = p.executeQuery()) { while (r.next()) result.add(new Models.Question(r.getInt(1), r.getString(2))); }
            }
            return result;
        }
    }

    /** Stores only correctness flags, never the claimant's plaintext responses. */
    public double submitAnswers(int claimId, int claimantId, List<Models.Question> questions, List<String> answers) throws SQLException {
        if (questions.size() != answers.size() || questions.isEmpty()) throw new IllegalArgumentException("Every verification question needs an answer.");
        String claimSql = "SELECT attempt_count FROM claims WHERE id=? AND claimant_id=? AND status='VERIFICATION_REQUIRED'";
        String hashSql = "SELECT answer_hash FROM verification_questions WHERE id=?";
        String answerSql = "INSERT INTO claim_answers(claim_id,question_id,attempt_number,is_correct) VALUES(?,?,?,?)";
        String update = "UPDATE claims SET attempt_count=?,score=?,status=? WHERE id=?";
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement claim = c.prepareStatement(claimSql)) {
                claim.setInt(1, claimId); claim.setInt(2, claimantId);
                int attempt; try (ResultSet r = claim.executeQuery()) { if (!r.next()) throw new IllegalStateException("This verification is no longer available."); attempt = r.getInt(1) + 1; }
                int correct = 0;
                try (PreparedStatement hash = c.prepareStatement(hashSql); PreparedStatement save = c.prepareStatement(answerSql)) {
                    for (int i = 0; i < questions.size(); i++) {
                        hash.setInt(1, questions.get(i).id());
                        String expected; try (ResultSet r = hash.executeQuery()) { if (!r.next()) throw new SQLException("Invalid question"); expected = r.getString(1); }
                        boolean match = expected.equals(answerHash(answers.get(i)));
                        if (match) correct++;
                        save.setInt(1, claimId); save.setInt(2, questions.get(i).id()); save.setInt(3, attempt); save.setInt(4, match ? 1 : 0); save.addBatch();
                    }
                    save.executeBatch();
                }
                double score = Math.round((100.0 * correct / questions.size()) * 10.0) / 10.0;
                String status = score >= 60 ? "UNDER_REVIEW" : (attempt >= MAX_ATTEMPTS ? "REJECTED" : "VERIFICATION_REQUIRED");
                try (PreparedStatement p = c.prepareStatement(update)) {
                    p.setInt(1, attempt); p.setDouble(2, score); p.setString(3, status); p.setInt(4, claimId); p.executeUpdate();
                }
                log(c, claimId, claimantId, "VERIFICATION_SUBMITTED", "Attempt " + attempt + " recorded; score retained for administrator review.");
                c.commit();
                return score;
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }

    public List<Models.Claim> claimsForStudent(int studentId) throws SQLException { return claims("WHERE c.claimant_id=?", studentId); }
    public List<Models.Claim> allClaims() throws SQLException { return claims("", null); }

    private List<Models.Claim> claims(String condition, Integer value) throws SQLException {
        String sql = "SELECT c.id,c.found_id,f.item_name,u.name claimant,c.claimant_id,c.status,c.attempt_count,c.score,c.admin_note "
                + "FROM claims c JOIN found_items f ON f.id=c.found_id JOIN users u ON u.id=c.claimant_id " + condition + " ORDER BY f.id,c.score DESC,c.id";
        List<Models.Claim> result = new ArrayList<>();
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement(sql)) {
            if (value != null) p.setInt(1, value);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) result.add(new Models.Claim(r.getInt("id"), r.getInt("found_id"), r.getString("item_name"),
                        r.getString("claimant"), r.getInt("claimant_id"), r.getString("status"), r.getInt("attempt_count"), r.getDouble("score"), r.getString("admin_note")));
            }
        }
        return result;
    }

    public void approveClaim(int claimId, int adminId, String note) throws SQLException {
        String check = "SELECT score,status FROM claims WHERE id=?";
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            requireAdmin(c, adminId);
            try (PreparedStatement p = c.prepareStatement(check)) {
                p.setInt(1, claimId); try (ResultSet r = p.executeQuery()) {
                    if (!r.next() || r.getDouble(1) < 60 || !("UNDER_REVIEW".equals(r.getString(2)) || "MORE_INFORMATION_REQUESTED".equals(r.getString(2))))
                        throw new IllegalStateException("Only a qualifying claim under review can be approved.");
                }
            }
            updateClaim(c, claimId, "APPROVED", note); log(c, claimId, adminId, "ADMIN_APPROVED", note); c.commit();
        }
    }

    public void rejectClaim(int claimId, int adminId, String note) throws SQLException {
        try (Connection c = connection()) { c.setAutoCommit(false); requireAdmin(c, adminId); updateClaim(c, claimId, "REJECTED", note); log(c, claimId, adminId, "ADMIN_REJECTED", note); c.commit(); }
    }

    /** The administrator may request physical evidence before making the final decision. */
    public void requestMoreInformation(int claimId, int adminId, String note) throws SQLException {
        try (Connection c = connection()) {
            c.setAutoCommit(false); requireAdmin(c, adminId);
            updateClaim(c, claimId, "MORE_INFORMATION_REQUESTED", note);
            log(c, claimId, adminId, "MORE_INFORMATION_REQUESTED", note); c.commit();
        }
    }

    /** The item becomes RETURNED only after the administrator confirms physical handover. */
    public void confirmHandover(int claimId, int adminId, String note) throws SQLException {
        String itemSql = "SELECT found_id,status FROM claims WHERE id=?";
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            requireAdmin(c, adminId);
            int foundId;
            try (PreparedStatement p = c.prepareStatement(itemSql)) {
                p.setInt(1, claimId); try (ResultSet r = p.executeQuery()) {
                    if (!r.next() || !"APPROVED".equals(r.getString("status"))) throw new IllegalStateException("Approve the claim before confirming handover.");
                    foundId = r.getInt("found_id");
                }
            }
            updateClaim(c, claimId, "COMPLETED", note);
            try (PreparedStatement p = c.prepareStatement("UPDATE found_items SET status='RETURNED' WHERE id=?")) { p.setInt(1, foundId); p.executeUpdate(); }
            try (PreparedStatement p = c.prepareStatement("UPDATE claims SET status='REJECTED',admin_note='Item returned to a verified claimant.' WHERE found_id=? AND id<>? AND status NOT IN ('REJECTED','COMPLETED')")) {
                p.setInt(1, foundId); p.setInt(2, claimId); p.executeUpdate();
            }
            log(c, claimId, adminId, "HANDOVER_CONFIRMED", note); c.commit();
        }
    }

    private void updateClaim(Connection c, int claimId, String status, String note) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE claims SET status=?,admin_note=? WHERE id=?")) {
            p.setString(1, status); p.setString(2, note == null ? "" : note.trim()); p.setInt(3, claimId); p.executeUpdate();
        }
    }

    private void log(Connection c, int claimId, int actorId, String action, String note) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO audit_log(claim_id,actor_id,action,note) VALUES(?,?,?,?)")) {
            p.setInt(1, claimId); p.setInt(2, actorId); p.setString(3, action); p.setString(4, note); p.executeUpdate();
        }
    }

    private void requireAdmin(Connection c, int userId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT role FROM users WHERE id=?")) {
            p.setInt(1, userId); try (ResultSet r = p.executeQuery()) {
                if (!r.next() || !"ADMIN".equals(r.getString(1))) throw new SecurityException("Administrator permission is required.");
            }
        }
    }

    private Models.LostItem lost(ResultSet r) throws SQLException {
        return new Models.LostItem(r.getInt("id"), r.getInt("user_id"), r.getString("item_name"), r.getString("category"),
                r.getString("colour"), r.getString("lost_location"), LocalDate.parse(r.getString("lost_date")), r.getString("description"), r.getString("status"));
    }

    private Models.FoundItem found(ResultSet r) throws SQLException {
        return new Models.FoundItem(r.getInt("id"), r.getString("item_name"), r.getString("category"), r.getString("colour"),
                r.getString("found_location"), LocalDate.parse(r.getString("found_date")), r.getString("public_description"), r.getString("status"));
    }

    private static String answerHash(String value) { return sha256(value.trim().toLowerCase()); }
    private static String sha256(String value) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes())); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String passwordHash(String password) {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + pbkdf2(password.toCharArray(), salt);
    }
    private static boolean verifyPassword(String password, String stored) {
        String[] parts = stored.split(":", 2); return parts.length == 2 && pbkdf2(password.toCharArray(), Base64.getDecoder().decode(parts[0])).equals(parts[1]);
    }
    private static String pbkdf2(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, 65_536, 256);
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) { throw new IllegalStateException("Password hashing is unavailable", e); }
    }
}
