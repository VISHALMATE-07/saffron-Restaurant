package com.saffron;

import java.io.IOException;
import java.sql.SQLException;

/** Entry point: prepares the database, starts the local web server, opens the browser. */
public class App {
    public static void main(String[] args) {
        try {
            Db.init();
        } catch (SQLException e) {
            System.err.println("Could not open or create the SQLite database.");
            System.err.println("Reason: " + e.getMessage());
            System.exit(1);
            return;
        }
        System.out.println("Database file: " + Db.filePath());

        try {
            Ui ui = new Ui(new Dao());
            int port = ui.start();
            String url = "http://localhost:" + port + "/";
            System.out.println();
            System.out.println("SAFFRON is running at " + url);
            System.out.println("Admin password: " + Ui.adminPasswordHint());
            System.out.println("Press Ctrl+C in this terminal to stop.");
            Ui.openBrowser(url);
        } catch (IOException e) {
            System.err.println("Could not start the web server: " + e.getMessage());
            System.err.println("Another program may be using ports 8080-8090. Close it and try again.");
            System.exit(1);
        }
    }
}
