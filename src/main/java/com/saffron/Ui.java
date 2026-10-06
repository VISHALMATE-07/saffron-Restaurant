package com.saffron;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * The user interface layer: a tiny web server (built into the JDK, no extra library)
 * that serves index.html / style.css / script.js and a few /api/... endpoints.
 * The browser pages call these endpoints, and the endpoints call Dao (SQLite).
 */
public class Ui {
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";

    private final Dao dao;
    private final Set<String> adminTokens = ConcurrentHashMap.newKeySet();
    private HttpServer server;

    public Ui(Dao dao) {
        this.dao = dao;
    }

    private static String adminPassword() {
        String p = System.getenv("SAFFRON_ADMIN_PASSWORD");
        return (p == null || p.isBlank()) ? DEFAULT_ADMIN_PASSWORD : p;
    }

    public static String adminPasswordHint() {
        return adminPassword().equals(DEFAULT_ADMIN_PASSWORD) ? DEFAULT_ADMIN_PASSWORD : "(set by SAFFRON_ADMIN_PASSWORD)";
    }

    @FunctionalInterface
    private interface Handler {
        void run(HttpExchange ex) throws Exception;
    }

    /** Starts the server on the first free port from 8080 to 8090 and returns that port. */
    public int start() throws IOException {
        int port = -1;
        for (int p = 8080; p <= 8090 && server == null; p++) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", p), 0);
                port = p;
            } catch (BindException e) {
                // port busy, try the next one
            }
        }
        if (server == null) {
            throw new IOException("No free port between 8080 and 8090.");
        }

        route("/", this::serveStatic);
        route("/api/menu", this::apiMenu);
        route("/api/order", this::apiPlaceOrder);
        route("/api/admin/login", this::apiAdminLogin);
        route("/api/admin/stats", this::apiAdminStats);
        route("/api/admin/orders", this::apiAdminOrders);
        route("/api/admin/order-items", this::apiAdminOrderItems);
        route("/api/admin/status", this::apiAdminStatus);
        route("/api/admin/delete", this::apiAdminDelete);

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return port;
    }

    public static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // fall through to the message below
        }
        System.out.println("Open this address in your browser: " + url);
    }

    /** Wraps every handler so errors become friendly JSON messages instead of crashes. */
    private void route(String path, Handler h) {
        server.createContext(path, ex -> {
            try {
                h.run(ex);
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, error(e.getMessage()));
            } catch (SQLException e) {
                System.err.println("Database error: " + e.getMessage());
                sendJson(ex, 500, error("Database error: " + e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(ex, 500, error("Unexpected server error."));
            } finally {
                ex.close();
            }
        });
    }

    // ------------------------------------------------------------ static files

    private void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }
        String type;
        switch (path) {
            case "/index.html" -> type = "text/html; charset=utf-8";
            case "/style.css" -> type = "text/css; charset=utf-8";
            case "/script.js" -> type = "application/javascript; charset=utf-8";
            default -> type = null;   // only these 3 files can ever be served
        }
        if (type == null) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = Ui.class.getResourceAsStream(path)) {
            if (in == null) {
                send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            send(ex, 200, type, in.readAllBytes());
        }
    }

    // ------------------------------------------------------------ customer API

    private void apiMenu(HttpExchange ex) throws Exception {
        if (!method(ex, "GET")) return;
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Models.MenuItem m : dao.getMenuItems()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(m.id())
              .append(",\"name\":").append(q(m.name()))
              .append(",\"category\":").append(q(m.category()))
              .append(",\"price\":").append(m.price()).append('}');
        }
        sendJson(ex, 200, sb.append(']').toString());
    }

    /** Body (form encoded): name=...&phone=...&items=1:2,5:1   (menuId:quantity pairs) */
    private void apiPlaceOrder(HttpExchange ex) throws Exception {
        if (!method(ex, "POST")) return;
        Map<String, String> f = parseForm(readBody(ex));
        List<Models.CartLine> lines = new ArrayList<>();
        String items = f.getOrDefault("items", "").trim();
        if (!items.isEmpty()) {
            try {
                for (String part : items.split(",")) {
                    String[] kv = part.split(":");
                    lines.add(new Models.CartLine(Integer.parseInt(kv[0].trim()), Integer.parseInt(kv[1].trim())));
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid cart data.");
            }
        }
        Models.Order o = dao.placeOrder(f.get("name"), f.get("phone"), lines);
        sendJson(ex, 200, "{\"ok\":true,\"orderId\":" + o.id() + ",\"total\":" + o.totalAmount() + "}");
    }

    // ------------------------------------------------------------ admin API

    private void apiAdminLogin(HttpExchange ex) throws Exception {
        if (!method(ex, "POST")) return;
        Map<String, String> f = parseForm(readBody(ex));
        if (adminPassword().equals(f.get("password"))) {
            String token = UUID.randomUUID().toString();
            adminTokens.add(token);
            sendJson(ex, 200, "{\"ok\":true,\"token\":" + q(token) + "}");
        } else {
            sendJson(ex, 401, error("Wrong password."));
        }
    }

    private boolean isAdmin(HttpExchange ex) throws IOException {
        String t = ex.getRequestHeaders().getFirst("X-Admin-Token");
        if (t != null && adminTokens.contains(t)) {
            return true;
        }
        sendJson(ex, 401, error("Admin login required."));
        return false;
    }

    private void apiAdminStats(HttpExchange ex) throws Exception {
        if (!method(ex, "GET") || !isAdmin(ex)) return;
        sendJson(ex, 200, "{\"totalOrders\":" + dao.getOrderCount(null)
                + ",\"totalSales\":" + dao.getTotalSales()
                + ",\"pending\":" + dao.getOrderCount("Pending")
                + ",\"preparing\":" + dao.getOrderCount("Preparing")
                + ",\"completed\":" + dao.getOrderCount("Completed")
                + ",\"cancelled\":" + dao.getOrderCount("Cancelled") + "}");
    }

    private void apiAdminOrders(HttpExchange ex) throws Exception {
        if (!method(ex, "GET") || !isAdmin(ex)) return;
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Models.Order o : dao.getOrders()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(o.id())
              .append(",\"name\":").append(q(o.customerName()))
              .append(",\"phone\":").append(q(o.customerPhone()))
              .append(",\"total\":").append(o.totalAmount())
              .append(",\"status\":").append(q(o.status()))
              .append(",\"date\":").append(q(o.orderDate())).append('}');
        }
        sendJson(ex, 200, sb.append(']').toString());
    }

    private void apiAdminOrderItems(HttpExchange ex) throws Exception {
        if (!method(ex, "GET") || !isAdmin(ex)) return;
        Map<String, String> qs = parseForm(ex.getRequestURI().getRawQuery());
        int id = parseId(qs.get("id"));
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Models.OrderItem i : dao.getOrderItems(id)) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":").append(q(i.menuName()))
              .append(",\"quantity\":").append(i.quantity())
              .append(",\"price\":").append(i.price()).append('}');
        }
        sendJson(ex, 200, sb.append(']').toString());
    }

    private void apiAdminStatus(HttpExchange ex) throws Exception {
        if (!method(ex, "POST") || !isAdmin(ex)) return;
        Map<String, String> f = parseForm(readBody(ex));
        int id = parseId(f.get("id"));
        if (dao.updateOrderStatus(id, f.get("status"))) {
            sendJson(ex, 200, "{\"ok\":true}");
        } else {
            sendJson(ex, 404, error("Order #" + id + " not found."));
        }
    }

    private void apiAdminDelete(HttpExchange ex) throws Exception {
        if (!method(ex, "POST") || !isAdmin(ex)) return;
        Map<String, String> f = parseForm(readBody(ex));
        int id = parseId(f.get("id"));
        if (dao.deleteOrder(id)) {
            sendJson(ex, 200, "{\"ok\":true}");
        } else {
            sendJson(ex, 404, error("Order #" + id + " not found."));
        }
    }

    // ------------------------------------------------------------ helpers

    private static int parseId(String s) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid order id.");
        }
    }

    private static boolean method(HttpExchange ex, String expected) throws IOException {
        if (ex.getRequestMethod().equalsIgnoreCase(expected)) {
            return true;
        }
        sendJson(ex, 405, error("Use " + expected + " for this address."));
        return false;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readNBytes(100_000), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseForm(String s) {
        Map<String, String> map = new HashMap<>();
        if (s == null || s.isEmpty()) return map;
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(k, v);
        }
        return map;
    }

    private static String error(String msg) {
        return "{\"ok\":false,\"error\":" + q(msg == null ? "Error" : msg) + "}";
    }

    /** Turns a Java string into a quoted, escaped JSON string. */
    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
