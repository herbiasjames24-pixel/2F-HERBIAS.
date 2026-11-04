package com.herbias.gasstation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * ACTIVITY 1 - Gasoline Station Management System
 * Single entry point + DatabaseInitializer + multi-role Authentication
 * + UserDao (getAllUsers / getPendingUsers / approveUser) + AdminMenu.
 *
 * Everything for this activity lives in this ONE file (Main.java).
 * Database: students.db (SQLite), created automatically in the project folder.
 */
public class Main {

    static final String DB_URL = "jdbc:sqlite:students.db";

    // ------------------------------------------------------------------
    // SINGLE ENTRY POINT
    // ------------------------------------------------------------------
    public static void main(String[] args) {
        if (!DatabaseInitializer.initialize()) {
            System.out.println("Database initialization failed. Application cannot start.");
            return;
        }
        Scanner scanner = new Scanner(System.in);
        new Authentication(scanner, new UserDao()).start();
        scanner.close();
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /** Opens a connection with SQLite Foreign Key constraints ENABLED. */
    static Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(DB_URL);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON;");
        }
        return conn;
    }

    static String hash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ------------------------------------------------------------------
    // MODEL
    // ------------------------------------------------------------------
    record User(int id, String username, String fullName, String role, String status) { }

    // ------------------------------------------------------------------
    // DATABASE INITIALIZER (guard for the single entry point)
    // ------------------------------------------------------------------
    static class DatabaseInitializer {

        static boolean initialize() {
            try (Connection conn = getConnection(); Statement st = conn.createStatement()) {

                // Confirm Foreign Keys are really ON
                try (ResultSet rs = st.executeQuery("PRAGMA foreign_keys;")) {
                    if (rs.next() && rs.getInt(1) == 1) {
                        System.out.println("[DB] Foreign key constraints: ENABLED");
                    } else {
                        System.out.println("[DB] Foreign key constraints could not be enabled.");
                        return false;
                    }
                }

                st.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        username      TEXT NOT NULL UNIQUE,
                        password_hash TEXT NOT NULL,
                        full_name     TEXT NOT NULL,
                        roles         TEXT NOT NULL CHECK (roles IN ('ADMIN','CASHIER','CLERK')),
                        status        TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('APPROVED','PENDING')),
                        created_at    TEXT DEFAULT CURRENT_TIMESTAMP
                    )""");

                st.execute("""
                    CREATE TABLE IF NOT EXISTS fuel_products (
                        product_id      INTEGER PRIMARY KEY AUTOINCREMENT,
                        product_name    TEXT NOT NULL UNIQUE,
                        fuel_type       TEXT NOT NULL,
                        price_per_liter REAL NOT NULL,
                        stock_liters    REAL NOT NULL DEFAULT 0,
                        reorder_level   REAL NOT NULL DEFAULT 0,
                        status          TEXT NOT NULL DEFAULT 'ACTIVE'
                    )""");

                st.execute("""
                    CREATE TABLE IF NOT EXISTS sales (
                        sale_id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        product_id     INTEGER NOT NULL,
                        cashier_id     INTEGER NOT NULL,
                        liters         REAL NOT NULL,
                        unit_price     REAL NOT NULL,
                        total_amount   REAL NOT NULL,
                        payment_method TEXT NOT NULL,
                        sale_datetime  TEXT DEFAULT CURRENT_TIMESTAMP,
                        is_void        INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (product_id) REFERENCES fuel_products(product_id),
                        FOREIGN KEY (cashier_id) REFERENCES users(id)
                    )""");

                seedIfEmpty(conn);
                return true;

            } catch (SQLException e) {
                System.out.println("[DB] Initialization error: " + e.getMessage());
                return false;
            }
        }

        private static void seedIfEmpty(Connection conn) throws SQLException {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
                if (rs.next() && rs.getInt(1) > 0) return;
            }

            String sql = "INSERT INTO users (username, password_hash, full_name, roles, status) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                Object[][] seed = {
                    {"admin",  "admin123", "Station Manager", "ADMIN",   "APPROVED"},
                    {"ana",    "ana123",   "Ana Reyes",       "CASHIER", "APPROVED"},
                    {"maria",  "maria123", "Maria Santos",    "CASHIER", "PENDING"},
                    {"jose",   "jose123",  "Jose Dela Cruz",  "CLERK",   "PENDING"},
                    {"pedro",  "pedro123", "Pedro Garcia",    "CLERK",   "PENDING"}
                };
                for (Object[] row : seed) {
                    ps.setString(1, (String) row[0]);
                    ps.setString(2, hash((String) row[1]));
                    ps.setString(3, (String) row[2]);
                    ps.setString(4, (String) row[3]);
                    ps.setString(5, (String) row[4]);
                    ps.executeUpdate();
                }
            }

            String fuelSql = "INSERT INTO fuel_products (product_name, fuel_type, price_per_liter, stock_liters, reorder_level) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(fuelSql)) {
                Object[][] fuels = {
                    {"Unleaded", "GASOLINE", 62.50, 5000.0, 1000.0},
                    {"Premium",  "GASOLINE", 68.75, 4000.0, 800.0},
                    {"Diesel",   "DIESEL",   58.20, 6000.0, 1200.0}
                };
                for (Object[] f : fuels) {
                    ps.setString(1, (String) f[0]);
                    ps.setString(2, (String) f[1]);
                    ps.setDouble(3, (Double) f[2]);
                    ps.setDouble(4, (Double) f[3]);
                    ps.setDouble(5, (Double) f[4]);
                    ps.executeUpdate();
                }
            }
            System.out.println("[DB] Sample data inserted (login: admin / admin123).");
        }
    }

    // ------------------------------------------------------------------
    // USER DAO
    // ------------------------------------------------------------------
    static class UserDao {

        private User map(ResultSet rs) throws SQLException {
            return new User(rs.getInt("id"), rs.getString("username"),
                    rs.getString("full_name"), rs.getString("roles"), rs.getString("status"));
        }

        /** All registered accounts regardless of role. */
        List<User> getAllUsers() {
            List<User> list = new ArrayList<>();
            String sql = "SELECT id, username, full_name, roles, status FROM users ORDER BY id";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            } catch (SQLException e) {
                System.out.println("Error loading users: " + e.getMessage());
            }
            return list;
        }

        /** Accounts with status = 'PENDING' across all roles. */
        List<User> getPendingUsers() {
            List<User> list = new ArrayList<>();
            String sql = "SELECT id, username, full_name, roles, status FROM users WHERE status = ? ORDER BY id";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, "PENDING");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(map(rs));
                }
            } catch (SQLException e) {
                System.out.println("Error loading pending users: " + e.getMessage());
            }
            return list;
        }

        /** PENDING -> APPROVED. Returns true if a row was updated. */
        boolean approveUser(int userId) {
            String sql = "UPDATE users SET status = 'APPROVED' WHERE id = ?;";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                System.out.println("Error approving user: " + e.getMessage());
                return false;
            }
        }

        // ---- used by Authentication ----

        User authenticate(String username, String password) {
            String sql = "SELECT id, username, full_name, roles, status FROM users WHERE username = ? AND password_hash = ?";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username);
                ps.setString(2, hash(password));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return map(rs);
                }
            } catch (SQLException e) {
                System.out.println("Login error: " + e.getMessage());
            }
            return null;
        }

        boolean usernameExists(String username) {
            String sql = "SELECT 1 FROM users WHERE username = ?";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                return true;
            }
        }

        boolean register(String username, String password, String fullName, String role) {
            String sql = "INSERT INTO users (username, password_hash, full_name, roles, status) VALUES (?, ?, ?, ?, 'PENDING')";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username);
                ps.setString(2, hash(password));
                ps.setString(3, fullName);
                ps.setString(4, role);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                System.out.println("Registration error: " + e.getMessage());
                return false;
            }
        }
    }

    // ------------------------------------------------------------------
    // AUTHENTICATION (multi-role login + UI routing)
    // ------------------------------------------------------------------
    static class Authentication {
        private final Scanner scanner;
        private final UserDao dao;

        Authentication(Scanner scanner, UserDao dao) {
            this.scanner = scanner;
            this.dao = dao;
        }

        void start() {
            while (true) {
                System.out.println("\n=== GASOLINE STATION MANAGEMENT SYSTEM ===");
                System.out.println("1. Login");
                System.out.println("2. Register (requires admin approval)");
                System.out.println("0. Exit");
                System.out.print("Choose: ");
                String choice = scanner.hasNextLine() ? scanner.nextLine().trim() : "0";
                switch (choice) {
                    case "1" -> login();
                    case "2" -> register();
                    case "0" -> { System.out.println("Goodbye!"); return; }
                    default -> System.out.println("Invalid choice.");
                }
            }
        }

        private void login() {
            System.out.print("Username: ");
            String username = scanner.nextLine().trim();
            System.out.print("Password: ");
            String password = scanner.nextLine();

            User user = dao.authenticate(username, password);
            if (user == null) {
                System.out.println("Invalid username or password.");
                return;
            }
            if (!"APPROVED".equals(user.status())) {
                System.out.println("Your account is still PENDING approval by the administrator.");
                return;
            }
            System.out.println("\nWelcome, " + user.fullName() + " (" + user.role() + ")");
            route(user);
        }

        /** Routes the logged-in user to the menu of their role. */
        private void route(User user) {
            switch (user.role()) {
                case "ADMIN"   -> new AdminMenu(scanner, dao, user).show();
                case "CASHIER" -> placeholderMenu("CASHIER MENU", "Record fuel sales / view fuel prices");
                case "CLERK"   -> placeholderMenu("INVENTORY CLERK MENU", "Record deliveries / view stock");
                default        -> System.out.println("Unknown role: " + user.role());
            }
        }

        private void placeholderMenu(String title, String info) {
            System.out.println("\n--- " + title + " ---");
            System.out.println("(" + info + " - coming in a later deliverable)");
            System.out.print("Press ENTER to log out...");
            scanner.nextLine();
        }

        private void register() {
            System.out.print("Choose username: ");
            String username = scanner.nextLine().trim();
            if (username.isEmpty() || dao.usernameExists(username)) {
                System.out.println("Username is empty or already taken.");
                return;
            }
            System.out.print("Choose password: ");
            String password = scanner.nextLine();
            if (password.isEmpty()) {
                System.out.println("Password cannot be empty.");
                return;
            }
            System.out.print("Full name: ");
            String fullName = scanner.nextLine().trim();
            if (fullName.isEmpty()) {
                System.out.println("Full name cannot be empty.");
                return;
            }
            System.out.print("Role (1 = CASHIER, 2 = CLERK): ");
            String r = scanner.nextLine().trim();
            String role = r.equals("1") ? "CASHIER" : r.equals("2") ? "CLERK" : null;
            if (role == null) {
                System.out.println("Invalid role.");
                return;
            }
            if (dao.register(username, password, fullName, role)) {
                System.out.println("Registration submitted. Wait for admin approval.");
            }
        }
    }

    // ------------------------------------------------------------------
    // ADMIN MENU
    // ------------------------------------------------------------------
    static class AdminMenu {
        private final Scanner scanner;
        private final UserDao dao;
        private final User currentAdmin;

        AdminMenu(Scanner scanner, UserDao dao, User currentAdmin) {
            this.scanner = scanner;
            this.dao = dao;
            this.currentAdmin = currentAdmin;
        }

        void show() {
            while (true) {
                System.out.println("\n===== ADMIN MENU (" + currentAdmin.username() + ") =====");
                System.out.println("1. View All System Users");
                System.out.println("2. View Pending Registrations");
                System.out.println("3. Approve User Account");
                System.out.println("0. Logout");
                int choice = readIntInput("Choose: ");
                switch (choice) {
                    case 1 -> viewAllUsers();
                    case 2 -> viewPendingUsers();
                    case 3 -> approveUserAccount();
                    case 0 -> { System.out.println("Logged out."); return; }
                    default -> System.out.println("Invalid choice.");
                }
            }
        }

        private void viewAllUsers() {
            System.out.println("\nALL SYSTEM USERS");
            printUserTable(dao.getAllUsers());
        }

        private void viewPendingUsers() {
            System.out.println("\nPENDING REGISTRATIONS");
            List<User> pending = dao.getPendingUsers();
            if (pending.isEmpty()) {
                System.out.println("No pending registrations.");
                return;
            }
            printUserTable(pending);
        }

        private void approveUserAccount() {
            List<User> pending = dao.getPendingUsers();
            if (pending.isEmpty()) {
                System.out.println("No pending accounts to approve.");
                return;
            }
            System.out.println("\nPENDING ACCOUNTS");
            printUserTable(pending);

            while (true) {
                int targetId = readIntInput("Enter Target User ID to approve (0 to cancel): ");
                if (targetId == 0) {
                    System.out.println("Approval cancelled.");
                    return;
                }
                boolean inPending = pending.stream().anyMatch(u -> u.id() == targetId);
                if (!inPending) {
                    System.out.println("ID " + targetId + " is not in the pending list. Try again.");
                    continue;
                }
                if (dao.approveUser(targetId)) {
                    System.out.println("SUCCESS: User ID " + targetId + " has been APPROVED.");
                } else {
                    System.out.println("Approval failed.");
                }
                return;
            }
        }

        /** Safe integer reader - never crashes on letters/symbols. */
        private int readIntInput(String prompt) {
            while (true) {
                System.out.print(prompt);
                if (!scanner.hasNextLine()) return 0;
                String line = scanner.nextLine().trim();
                try {
                    return Integer.parseInt(line);
                } catch (NumberFormatException e) {
                    System.out.println("Invalid input. Please enter a number.");
                }
            }
        }

        /** Structured table renderer for any list of users. */
        static void printUserTable(List<User> users) {
            String border = "+-------+-----------------+--------------+------------+";
            System.out.println(border);
            System.out.printf("| %-5s | %-15s | %-12s | %-10s |%n", "ID", "Username", "Role", "Status");
            System.out.println(border);
            for (User u : users) {
                System.out.printf("| %-5s | %-15s | %-12s | %-10s |%n",
                        u.id(), u.username(), u.role(), u.status());
            }
            System.out.println(border);
            System.out.println("Total: " + users.size() + " user(s)");
        }
    }
}
