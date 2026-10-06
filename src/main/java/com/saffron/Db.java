package com.saffron;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Opens the SQLite connection and creates the tables on first run. */
public final class Db {
    // The file saffron.db lives in the folder the program is started from.
    private static final String URL = "jdbc:sqlite:saffron.db";

    private Db() {}

    public static String filePath() {
        return new File("saffron.db").getAbsolutePath();
    }

    public static Connection getConnection() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite driver not found. Check that the sqlite-jdbc dependency is loaded.", e);
        }
        Connection c = DriverManager.getConnection(URL);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }

    /** Creates missing tables and inserts sample menu items ONLY if the menu is empty. */
    public static void init() throws SQLException {
        try (Connection c = getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS menu (
                    id       INTEGER PRIMARY KEY AUTOINCREMENT,
                    name     TEXT NOT NULL,
                    category TEXT NOT NULL,
                    price    REAL NOT NULL CHECK (price >= 0)
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    customer_name  TEXT NOT NULL,
                    customer_phone TEXT NOT NULL,
                    total_amount   REAL NOT NULL,
                    status         TEXT NOT NULL DEFAULT 'Pending',
                    order_date     TEXT NOT NULL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS order_items (
                    id       INTEGER PRIMARY KEY AUTOINCREMENT,
                    order_id INTEGER NOT NULL,
                    menu_id  INTEGER NOT NULL,
                    quantity INTEGER NOT NULL CHECK (quantity > 0),
                    price    REAL NOT NULL,
                    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
                    FOREIGN KEY (menu_id)  REFERENCES menu(id)
                )""");

            // First-ever run: make the first order number #1001 instead of #1.
            if (count(c, "SELECT COUNT(*) FROM orders") == 0
                    && count(c, "SELECT COUNT(*) FROM sqlite_sequence WHERE name = 'orders'") == 0) {
                st.execute("INSERT INTO sqlite_sequence(name, seq) VALUES ('orders', 1000)");
            }

            if (count(c, "SELECT COUNT(*) FROM menu") == 0) {
                seedMenu(c);
            }
        }
    }

    private static int count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void seedMenu(Connection c) throws SQLException {
        String[][] items = {
            {"Veg Manchurian", "Starters", "160"},
            {"Paneer Tikka", "Starters", "200"},
            {"Veg Spring Rolls", "Starters", "140"},
            {"Paneer Butter Masala", "Main Course", "220"},
            {"Dal Makhani", "Main Course", "190"},
            {"Masala Dosa", "Main Course", "120"},
            {"Veg Biryani", "Rice", "180"},
            {"Jeera Rice", "Rice", "110"},
            {"Butter Naan", "Breads", "50"},
            {"Garlic Naan", "Breads", "60"},
            {"Tandoori Roti", "Breads", "30"},
            {"Cold Coffee", "Beverages", "90"},
            {"Masala Chai", "Beverages", "40"},
            {"Fresh Lime Soda", "Beverages", "70"},
        };
        boolean oldAuto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO menu(name, category, price) VALUES (?, ?, ?)")) {
            for (String[] it : items) {
                ps.setString(1, it[0]);
                ps.setString(2, it[1]);
                ps.setDouble(3, Double.parseDouble(it[2]));
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(oldAuto);
        }
    }
}
