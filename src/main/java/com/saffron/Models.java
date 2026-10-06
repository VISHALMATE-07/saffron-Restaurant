package com.saffron;

import java.util.List;

/** Simple data classes (Java records) used across the project. */
public final class Models {
    private Models() {}

    /** Allowed order statuses, in the order they normally progress. */
    public static final List<String> STATUSES = List.of("Pending", "Preparing", "Completed", "Cancelled");

    /** One row of the MENU table. */
    public record MenuItem(int id, String name, String category, double price) {}

    /** One row of the ORDERS table. */
    public record Order(int id, String customerName, String customerPhone,
                        double totalAmount, String status, String orderDate) {}

    /** One row of ORDER_ITEMS (menuName is joined in from MENU for display). */
    public record OrderItem(int id, int orderId, int menuId, String menuName,
                            int quantity, double price) {}

    /** One cart line sent by the browser: which menu item and how many. */
    public record CartLine(int menuId, int quantity) {}
}
