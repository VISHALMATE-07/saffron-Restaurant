=====================================================================
 SAFFRON RESTAURANT MANAGEMENT SYSTEM
 Java 21 + Maven + SQLite (JDBC) - College Mini Project
=====================================================================

WHAT IT IS
----------
A small restaurant ordering system. Customers browse the menu, add food
to a cart and place an order. The admin screen shows all orders and can
change their status. Everything is stored in a REAL SQLite database file
(saffron.db), so data stays after you close the program.

HOW IT WORKS (architecture, like MEDSTORE)
------------------------------------------
  Browser pages (index.html, style.css, script.js)
        |   (fetch calls to /api/...)
        v
  Ui.java   - tiny web server built into the JDK (no Tomcat, no Node)
        |
        v
  Dao.java  - ALL SQL (INSERT / SELECT / UPDATE / DELETE), PreparedStatement
        |
        v
  Db.java   - JDBC connection to saffron.db + creates tables + sample menu
        |
        v
  saffron.db (SQLite file)

  App.java    - starts everything and opens your browser
  Models.java - MenuItem, Order, OrderItem, CartLine data classes

The 4 screens (all inside one page, switched by the navbar):
  1. Home   2. Menu (+ add to cart)   3. Cart / Checkout   4. Admin

Database tables:
  MENU(id, name, category, price)
  ORDERS(id, customer_name, customer_phone, total_amount, status, order_date)
  ORDER_ITEMS(id, order_id, menu_id, quantity, price)   -> foreign keys to the others


=====================================================================
 STEP 1 - INSTALL THE TOOLS (one time)
=====================================================================
 1. Install JDK 21 (for example "Eclipse Temurin 21" from adoptium.net).
    Tick "Add to PATH" / "Set JAVA_HOME" in the installer if offered.
 2. Install Visual Studio Code (code.visualstudio.com).
 3. In VS Code open Extensions (Ctrl+Shift+X), search
    "Extension Pack for Java" (by Microsoft) and install it.
    This pack already contains Maven support, so you do NOT need to
    install Maven separately to run the project from VS Code.
 4. Close and reopen VS Code.

 Check Java: open a terminal (Win+R, type cmd, Enter) and run:
     java -version
     javac -version
 Both should say 21.


=====================================================================
 STEP 2 - OPEN THE PROJECT
=====================================================================
 1. Extract the ZIP.
 2. In VS Code: File > Open Folder... and choose the folder named
    "saffron"  (the one that CONTAINS pom.xml). Important: open
    "saffron", not the outer "SAFFRON" folder.
 3. If VS Code asks "Do you trust the authors?" click Yes.
 4. Wait for the Java extension to finish. The first time it downloads the
    SQLite driver from the internet (a few seconds to a minute; bottom
    status bar shows progress). You need internet only for this.


=====================================================================
 STEP 3 - RUN IT  (pick ONE option)
=====================================================================

 OPTION A - VS Code (easiest)
   1. Open src/main/java/com/saffron/App.java
   2. Click "Run" above the line "public static void main", or press F5.
   3. Your web browser opens at http://localhost:8080/
      (If it does not open by itself, copy the address printed in the
      terminal into your browser.)

 OPTION B - double-click run.bat (needs only the JDK, no Maven)
   Double-click run.bat inside the "saffron" folder. The first time it
   downloads the SQLite driver into a "lib" folder, compiles the code
   and starts the app.

 OPTION C - Maven command line (only if Maven is installed)
   Open a terminal in the "saffron" folder and run:
     mvn clean package
     java -jar target\saffron.jar
   or, without building a jar:
     mvn compile exec:java

 To STOP the program: click the red square in VS Code, or press Ctrl+C in
 the terminal, or close the run.bat window.

 Admin password: admin123


=====================================================================
 WHERE IS THE DATABASE?
=====================================================================
 The file is  saffron.db  in the "saffron" folder (the folder the program
 is started from). The exact path is printed in the terminal when the app
 starts ("Database file: ...").

 It is already included with tables and sample menu items. If you delete
 it, the program creates a new one automatically on the next start:
   - Db.init() runs CREATE TABLE IF NOT EXISTS for MENU, ORDERS, ORDER_ITEMS
   - sample menu items are inserted ONLY when the MENU table is empty
 Existing data is never deleted at startup. Order numbers start at #1001.

 TIP: To start clean for the demo, stop the app, delete saffron.db, and run
 again.


=====================================================================
 HOW TO TEST (the demo for your teacher)
=====================================================================
 1. Run the app. The Home page opens.
 2. Click "Explore Menu". The menu comes from the SQLite MENU table.
 3. Click "Add to Cart" on a few items. The Cart badge counts them.
 4. Click "Cart". Use + / - to change quantity. Check the total.
 5. Type a name and a 10-digit phone number, click "Place Order".
    You see "Order Placed Successfully! Order ID: #1001". Cart is cleared.
 6. Click "Admin", log in with  admin123 .
    You see the stats (total orders, total sales, pending, completed)
    and your order in the table. Click "Items" to see its ORDER_ITEMS.
 7. Change the status in the dropdown: Pending > Preparing > Completed
    (or Cancelled). The stats update. "Delete" removes an order.
 8. STOP the app completely (Ctrl+C / red square).
 9. Run it again, open Admin, log in. Your order and its latest status
    are still there - they were saved in saffron.db.

 Error checks you can show: empty cart, empty name, phone with fewer than
 10 digits, wrong admin password - each shows a clear message.

 Optional - look inside the database: install the VS Code extension
 "SQLite Viewer" and click saffron.db, or use "DB Browser for SQLite".


=====================================================================
 HOW MAVEN IS USED
=====================================================================
 pom.xml tells Maven: use Java 21, and download ONE library
 (org.xerial:sqlite-jdbc) that lets Java talk to SQLite.
   mvn clean package   -> compiles and builds target\saffron.jar
                          (a single jar that includes the driver)
 Downloaded libraries are cached in  C:\Users\<you>\.m2


=====================================================================
 TROUBLESHOOTING
=====================================================================
 * "Port 8080 busy": the app automatically tries ports 8080-8090. Use the
   address printed in the terminal.
 * "SQLite driver not found": VS Code has not finished downloading the
   dependency. Wait for the bottom status bar, then in VS Code press
   Ctrl+Shift+P > "Java: Clean Java Language Server Workspace" > Restart.
   Or just use run.bat.
 * Page is blank or shows old content: press Ctrl+F5 in the browser.
 * Changed the Java code but nothing changed: stop the app and run it again.
 * Browser says "Cannot reach the SAFFRON server": the Java program was
   stopped. Start it again.
 * To change the admin password: set an environment variable
   SAFFRON_ADMIN_PASSWORD before starting (default is admin123).

 FILES
 -----
 pom.xml                        Maven configuration
 saffron.db                     SQLite database
 run.bat                        run without Maven
 src/main/java/com/saffron/     App, Db, Dao, Models, Ui
 src/main/resources/            index.html, style.css, script.js
 .vscode/launch.json            VS Code run configuration
