"use strict";

// ---------- state (the cart lives in memory; ORDERS are saved in SQLite) ----------
const state = {
  menu: [],            // loaded from the SQLite MENU table via /api/menu
  cart: new Map(),     // menuId -> quantity
  category: "All",
  token: null          // admin token after login
};

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const esc = s => String(s).replace(/[&<>"']/g, c =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const money = n => "\u20B9" + (Number.isInteger(+n) ? String(+n) : (+n).toFixed(2));
const EMOJI = { "Starters": "\uD83E\uDD57", "Main Course": "\uD83C\uDF5B", "Rice": "\uD83C\uDF5A",
                "Breads": "\uD83E\uDED3", "Beverages": "\u2615" };

function toast(msg, bad = false) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.toggle("bad", bad);
  t.classList.add("show");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.remove("show"), 2200);
}

// ---------- talking to the Java server ----------
async function api(path, { method = "GET", form = null, admin = false } = {}) {
  const opts = { method, headers: {} };
  if (form) {
    opts.headers["Content-Type"] = "application/x-www-form-urlencoded";
    opts.body = new URLSearchParams(form).toString();
  }
  if (admin && state.token) opts.headers["X-Admin-Token"] = state.token;

  let res;
  try {
    res = await fetch(path, opts);
  } catch (e) {
    throw new Error("Cannot reach the SAFFRON server. Is the Java application still running?");
  }
  let data = null;
  try { data = await res.json(); } catch (e) { /* no JSON body */ }

  if (res.status === 401 && admin) {
    state.token = null;
    renderAdmin();
  }
  if (!res.ok || (data && data.ok === false)) {
    throw new Error((data && data.error) || ("Request failed (" + res.status + ")"));
  }
  return data;
}

// ---------- routing between the 4 screens ----------
function route() {
  const h = (location.hash || "#home").slice(1);
  let view = h, anchor = null;
  if (h === "about" || h === "contact") { view = "home"; anchor = h; }
  if (!["home", "menu", "cart", "admin"].includes(view)) view = "home";

  $$(".view").forEach(v => v.classList.toggle("active", v.id === "view-" + view));
  $$("#nav a[data-nav]").forEach(a => a.classList.toggle("active", a.dataset.nav === view));
  $("#nav").classList.remove("open");

  if (anchor) document.getElementById(anchor).scrollIntoView({ behavior: "smooth" });
  else window.scrollTo(0, 0);

  if (view === "menu") loadMenu();
  if (view === "admin") renderAdmin();
}

// ---------- SCREEN 2: menu ----------
async function loadMenu() {
  try {
    state.menu = await api("/api/menu");
    renderMenu();
    renderCart();
  } catch (e) {
    $("#menuGrid").innerHTML = '<p class="error">' + esc(e.message) + "</p>";
  }
}

function renderMenu() {
  const cats = ["All", ...new Set(state.menu.map(m => m.category))];
  if (!cats.includes(state.category)) state.category = "All";
  $("#categoryBar").innerHTML = cats.map(c =>
    `<button class="chip ${c === state.category ? "active" : ""}" data-cat="${esc(c)}">${esc(c)}</button>`).join("");

  const items = state.menu.filter(m => state.category === "All" || m.category === state.category);
  $("#menuGrid").innerHTML = items.length ? items.map(m => `
    <article class="card dish">
      <div class="emoji">${EMOJI[m.category] || "\uD83C\uDF7D\uFE0F"}</div>
      <span class="tag">${esc(m.category)}</span>
      <h3>${esc(m.name)}</h3>
      <div class="dish-foot">
        <span class="price">${money(m.price)}</span>
        <button class="btn small" data-add="${m.id}">Add to Cart</button>
      </div>
    </article>`).join("") : '<p class="muted">No items in this category.</p>';
}

// ---------- SCREEN 3: cart ----------
function cartTotal() {
  let total = 0;
  for (const [id, q] of state.cart) {
    const m = state.menu.find(x => x.id === id);
    if (m) total += m.price * q;
  }
  return total;
}

function updateCartCount() {
  let n = 0;
  for (const q of state.cart.values()) n += q;
  $("#cartCount").textContent = n;
}

function addToCart(id) {
  const q = state.cart.get(id) || 0;
  if (q >= 50) { toast("Maximum 50 per item.", true); return; }
  state.cart.set(id, q + 1);
  const m = state.menu.find(x => x.id === id);
  toast((m ? m.name : "Item") + " added to cart");
  $("#orderSuccess").classList.add("hidden");
  updateCartCount();
  renderCart();
}

function changeQty(id, delta) {
  const q = (state.cart.get(id) || 0) + delta;
  if (q <= 0) state.cart.delete(id);
  else if (q > 50) { toast("Maximum 50 per item.", true); return; }
  else state.cart.set(id, q);
  updateCartCount();
  renderCart();
}

function renderCart() {
  const rows = [];
  for (const [id, q] of state.cart) {
    const m = state.menu.find(x => x.id === id);
    if (!m) continue;
    rows.push(`
      <tr>
        <td>${esc(m.name)}</td>
        <td>${money(m.price)}</td>
        <td><span class="qty">
          <button data-dec="${id}" aria-label="Decrease">&minus;</button>
          <strong>${q}</strong>
          <button data-inc="${id}" aria-label="Increase">+</button></span></td>
        <td>${money(m.price * q)}</td>
        <td><button class="icon-btn" data-rem="${id}" title="Remove">&#10005;</button></td>
      </tr>`);
  }
  const has = rows.length > 0;
  $("#cartBody").innerHTML = rows.join("");
  $("#cartTotal").textContent = money(cartTotal());
  $("#cartContent").classList.toggle("hidden", !has);
  $("#cartEmpty").classList.toggle("hidden", has);
}

async function placeOrder(e) {
  e.preventDefault();
  const err = $("#formError");
  err.textContent = "";
  const name = $("#custName").value.trim();
  const phone = $("#custPhone").value.replace(/[\s-]/g, "");

  if (state.cart.size === 0) { err.textContent = "Your cart is empty."; return; }
  if (name.length < 2) { err.textContent = "Please enter your name."; return; }
  if (!/^\d{10}$/.test(phone)) { err.textContent = "Phone number must be exactly 10 digits."; return; }

  const items = [...state.cart].map(([id, q]) => id + ":" + q).join(",");
  const btn = $("#placeBtn");
  btn.disabled = true;
  try {
    const r = await api("/api/order", { method: "POST", form: { name, phone, items } });
    state.cart.clear();
    updateCartCount();
    renderCart();
    $("#custName").value = "";
    $("#custPhone").value = "";
    const box = $("#orderSuccess");
    box.innerHTML = `<h3>Order Placed Successfully!</h3>
      <p class="oid">Order ID: #${r.orderId}</p>
      <p>Total: <strong>${money(r.total)}</strong></p>
      <p>Thank you for ordering from SAFFRON.</p>`;
    box.classList.remove("hidden");
    window.scrollTo(0, 0);
  } catch (ex) {
    err.textContent = ex.message;
  } finally {
    btn.disabled = false;
  }
}

// ---------- SCREEN 4: admin ----------
function renderAdmin() {
  const loggedIn = !!state.token;
  $("#adminLogin").classList.toggle("hidden", loggedIn);
  $("#adminDash").classList.toggle("hidden", !loggedIn);
  if (loggedIn) loadAdmin();
}

async function adminLogin(e) {
  e.preventDefault();
  $("#loginError").textContent = "";
  try {
    const r = await api("/api/admin/login", { method: "POST", form: { password: $("#adminPass").value } });
    state.token = r.token;
    $("#adminPass").value = "";
    renderAdmin();
  } catch (ex) {
    $("#loginError").textContent = ex.message;
  }
}

async function loadAdmin() {
  try {
    const [s, orders] = await Promise.all([
      api("/api/admin/stats", { admin: true }),
      api("/api/admin/orders", { admin: true })
    ]);
    const stat = (num, lbl) => `<div class="card stat"><div class="num">${num}</div><div class="lbl">${lbl}</div></div>`;
    $("#statCards").innerHTML =
      stat(s.totalOrders, "Total Orders") + stat(money(s.totalSales), "Total Sales") +
      stat(s.pending, "Pending Orders") + stat(s.completed, "Completed Orders");

    $("#ordersBody").innerHTML = orders.length ? orders.map(o => `
      <tr data-oid="${o.id}">
        <td><strong>#${o.id}</strong></td>
        <td>${esc(o.name)}</td>
        <td>${esc(o.phone)}</td>
        <td>${money(o.total)}</td>
        <td><select class="status st-${esc(o.status)}" data-status="${o.id}">
          ${["Pending", "Preparing", "Completed", "Cancelled"].map(st =>
            `<option ${st === o.status ? "selected" : ""}>${st}</option>`).join("")}
        </select></td>
        <td>${esc(o.date)}</td>
        <td class="actions">
          <button class="btn small outline" data-items="${o.id}">Items</button>
          <button class="btn small danger" data-del="${o.id}">Delete</button>
        </td>
      </tr>`).join("") : '<tr><td colspan="7" class="muted">No orders yet.</td></tr>';
  } catch (ex) {
    toast(ex.message, true);
  }
}

async function toggleItems(id, btn) {
  const row = btn.closest("tr");
  const next = row.nextElementSibling;
  if (next && next.classList.contains("items-row")) { next.remove(); return; }
  try {
    const items = await api("/api/admin/order-items?id=" + id, { admin: true });
    const tr = document.createElement("tr");
    tr.className = "items-row";
    tr.innerHTML = '<td colspan="7">' + (items.length
      ? items.map(i => `${esc(i.name)} &times; ${i.quantity} (${money(i.price)} each)`).join(" &nbsp;|&nbsp; ")
      : "No items.") + "</td>";
    row.after(tr);
  } catch (ex) { toast(ex.message, true); }
}

async function changeStatus(id, status) {
  try {
    await api("/api/admin/status", { method: "POST", admin: true, form: { id, status } });
    toast("Order #" + id + " is now " + status);
  } catch (ex) { toast(ex.message, true); }
  loadAdmin();
}

async function deleteOrder(id) {
  if (!confirm("Delete order #" + id + " permanently?")) return;
  try {
    await api("/api/admin/delete", { method: "POST", admin: true, form: { id } });
    toast("Order #" + id + " deleted");
  } catch (ex) { toast(ex.message, true); }
  loadAdmin();
}

// ---------- events ----------
document.addEventListener("click", e => {
  const t = e.target.closest("button");
  if (!t) return;
  if (t.dataset.add) addToCart(+t.dataset.add);
  else if (t.dataset.cat) { state.category = t.dataset.cat; renderMenu(); }
  else if (t.dataset.inc) changeQty(+t.dataset.inc, 1);
  else if (t.dataset.dec) changeQty(+t.dataset.dec, -1);
  else if (t.dataset.rem) { state.cart.delete(+t.dataset.rem); updateCartCount(); renderCart(); }
  else if (t.dataset.items) toggleItems(+t.dataset.items, t);
  else if (t.dataset.del) deleteOrder(+t.dataset.del);
  else if (t.id === "refreshBtn") loadAdmin();
  else if (t.id === "logoutBtn") { state.token = null; renderAdmin(); }
  else if (t.id === "navToggle") $("#nav").classList.toggle("open");
});
document.addEventListener("change", e => {
  if (e.target.dataset && e.target.dataset.status) changeStatus(+e.target.dataset.status, e.target.value);
});
$("#checkoutForm").addEventListener("submit", placeOrder);
$("#loginForm").addEventListener("submit", adminLogin);
window.addEventListener("hashchange", route);

// ---------- start ----------
loadMenu();
route();
