package com.saffron;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All database operations (SELECT / INSERT / UPDATE / DELETE) live here.
 * Bad user input throws IllegalArgumentException (message is shown to the user).
 * Database problems throw SQLException.
 */
public class Dao {
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ---------------------------------------------------------------- MENU

    public List<Models.MenuItem> getMenuItems() throws SQLException {
        List<Models.MenuItem> list = new ArrayList<>();
        String sql = "SELECT id, name, category, price FROM menu ORDER BY id";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Models.MenuItem(rs.getInt("id"), rs.getString("name"),
                        rs.getString("category"), rs.getDouble("price")));
            }
        }
        return list;
    }

    // -------------------------------------------------------------- ORDERS

    /**
     * Validates the customer + cart, then saves the order and its items in ONE
     * transaction (either everything is saved or nothing is).
     * Prices are read from the MENU table, never trusted from the browser.
     */
    public Models.Order placeOrder(String name, String phone, List<Models.CartLine> lines) throws SQLException {
        String cleanName = name == null ? "" : name.trim();
        String cleanPhone = phone == null ? "" : phone.replaceAll("[\\s-]", "");
        if (cleanName.length() < 2 || cleanName.length() > 50) {
            throw new IllegalArgumentException("Please enter your name (2 to 50 characters).");
        }
        if (!cleanPhone.matches("\\d{10}")) {
            throw new IllegalArgumentException("Phone number must be exactly 10 digits.");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Your cart is empty.");
        }

        // Merge duplicate menu ids and check quantities.
        Map<Integer, Integer> qtyById = new LinkedHashMap<>();
        for (Models.CartLine l : lines) {
            if (l.quantity() < 1) {
                throw new IllegalArgumentException("Invalid quantity. Minimum is 1.");
            }
            qtyById.merge(l.menuId(), l.quantity(), Integer::sum);
        }
        for (int q : qtyById.values()) {
            if (q > 50) {
                throw new IllegalArgumentException("Invalid quantity. Maximum is 50 per item.");
            }
        }

        String date = LocalDateTime.now().format(DATE_FMT);

        try (Connection c = Db.getConnection()) {
            c.setAutoCommit(false);
            try {
                Map<Integer, Double> priceById = new LinkedHashMap<>();
                double total = 0;
                try (PreparedStatement ps = c.prepareStatement("SELECT price FROM menu WHERE id = ?")) {
                    for (Map.Entry<Integer, Integer> e : qtyById.entrySet()) {
                        ps.setInt(1, e.getKey());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalArgumentException("Menu item #" + e.getKey() + " does not exist.");
                            }
                            double price = rs.getDouble(1);
                            priceById.put(e.getKey(), price);
                            total += price * e.getValue();
                        }
                    }
                }
                total = Math.round(total * 100.0) / 100.0;

                int orderId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO orders(customer_name, customer_phone, total_amount, status, order_date) "
                                + "VALUES (?, ?, ?, 'Pending', ?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, cleanName);
                    ps.setString(2, cleanPhone);
                    ps.setDouble(3, total);
                    ps.setString(4, date);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Could not read the new order id.");
                        }
                        orderId = keys.getInt(1);
                    }
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO order_items(order_id, menu_id, quantity, price) VALUES (?, ?, ?, ?)")) {
                    for (Map.Entry<Integer, Integer> e : qtyById.entrySet()) {
                        ps.setInt(1, orderId);
                        ps.setInt(2, e.getKey());
                        ps.setInt(3, e.getValue());
                        ps.setDouble(4, priceById.get(e.getKey()));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                c.commit();
                return new Models.Order(orderId, cleanName, cleanPhone, total, "Pending", date);
            } catch (SQLException | RuntimeException ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public List<Models.Order> getOrders() throws SQLException {
        List<Models.Order> list = new ArrayList<>();
        String sql = "SELECT id, customer_name, customer_phone, total_amount, status, order_date "
                + "FROM orders ORDER BY id DESC";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Models.Order(rs.getInt("id"), rs.getString("customer_name"),
                        rs.getString("customer_phone"), rs.getDouble("total_amount"),
                        rs.getString("status"), rs.getString("order_date")));
            }
        }
        return list;
    }

    public List<Models.OrderItem> getOrderItems(int orderId) throws SQLException {
        List<Models.OrderItem> list = new ArrayList<>();
        String sql = "SELECT oi.id, oi.order_id, oi.menu_id, m.name, oi.quantity, oi.price "
                + "FROM order_items oi JOIN menu m ON m.id = oi.menu_id "
                + "WHERE oi.order_id = ? ORDER BY oi.id";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Models.OrderItem(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                            rs.getString(4), rs.getInt(5), rs.getDouble(6)));
                }
            }
        }
        return list;
    }

    /** Returns true if an order was updated, false if the id does not exist. */
    public boolean updateOrderStatus(int orderId, String status) throws SQLException {
        if (!Models.STATUSES.contains(status)) {
            throw new IllegalArgumentException("Invalid status: " + status);
        }
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE orders SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setInt(2, orderId);
            return ps.executeUpdate() > 0;
        }
    }

    /** Deletes an order and its items. Returns false if the id does not exist. */
    public boolean deleteOrder(int orderId) throws SQLException {
        try (Connection c = Db.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM order_items WHERE order_id = ?")) {
                    ps.setInt(1, orderId);
                    ps.executeUpdate();
                }
                int rows;
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM orders WHERE id = ?")) {
                    ps.setInt(1, orderId);
                    rows = ps.executeUpdate();
                }
                c.commit();
                return rows > 0;
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    // --------------------------------------------------------------- STATS

    /** Total sales = sum of all orders that are not cancelled. */
    public double getTotalSales() throws SQLException {
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(total_amount), 0) FROM orders WHERE status <> 'Cancelled'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getDouble(1) : 0;
        }
    }

    /** Number of orders with the given status; pass null to count all orders. */
    public int getOrderCount(String status) throws SQLException {
        String sql = status == null
                ? "SELECT COUNT(*) FROM orders"
                : "SELECT COUNT(*) FROM orders WHERE status = ?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (status != null) {
                ps.setString(1, status);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
