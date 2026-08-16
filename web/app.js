/**
 * Culinary Order System – Customer Web App
 * 
 * Integrates with Firestore for real-time menu and order submission.
 * Firebase config is injected via environment variables at build time
 * (Netlify/Vercel) and exposed as window.FIREBASE_CONFIG.
 *
 * URL query parameter: ?table=<tableNumber>
 */

// ---------------------------------------------------------------------------
// Firebase config – Replace values via environment variable injection or
// by editing this object directly for local development.
// In production, set these as build-time env vars on Netlify/Vercel.
// ---------------------------------------------------------------------------
const FIREBASE_CONFIG = window.FIREBASE_CONFIG || {
  apiKey:            "YOUR_API_KEY",
  authDomain:        "YOUR_PROJECT.firebaseapp.com",
  projectId:         "YOUR_PROJECT_ID",
  storageBucket:     "YOUR_PROJECT.appspot.com",
  messagingSenderId: "YOUR_SENDER_ID",
  appId:             "YOUR_APP_ID"
};

// ---------------------------------------------------------------------------
// App State
// ---------------------------------------------------------------------------
const state = {
  tableNumber: "",
  sessionId:   "",    // Unique session ID for this customer's visit
  businessName: "",   // Loaded from settings; used for dynamic subtitle
  categories:  [],
  menuItems:   [],
  cart:        [],    // [{ menuItem, quantity, selectedToppings, notes, subtotal }]
  orders:      [],    // User's order history for this session
  activeCategory: "all",
  isLoading:   true,
  db:          null,
  ordersListener: null,
  qrisPollingTimer: null,  // Timer for payment status polling
  qrisCountdownTimer: null,  // Timer for the 5-minute amount-lock countdown
  currentPaymentOrderId: null,  // Store current order ID for payment tracking
  paymentMethodsEnabled: { QRIS: true, BANK_TRANSFER: true, CASHIER: true },  // Loaded from settings
  // Order type + location gating
  orderType: null,           // "DINE_IN" | "TAKE_AWAY" — chosen before the menu
  businessLocation: null,    // { latitude, longitude } — from settings
  orderRadiusMeters: null,   // max allowed distance in meters — from settings
  customerLocation: null,    // { latitude, longitude } — browser geolocation
  customerDistanceMeters: null,
  locationStatus: "unknown"  // "unknown" | "granted" | "denied"
};

// ---------------------------------------------------------------------------
// Initialise
// ---------------------------------------------------------------------------
document.addEventListener("DOMContentLoaded", async () => {
  parseTableFromUrl();
  await initFirebase();
  await loadSettings();
  showOrderTypeChooser();
  await loadMenu();
  await loadOrderHistory();
});

function parseTableFromUrl() {
  const params = new URLSearchParams(window.location.search);
  state.tableNumber = params.get("table") || "1";
  
  // Generate or retrieve session ID from localStorage
  // Session ID persists within the same browser tab/session for the same table
  const sessionStorageKey = `culinary_session_table_${state.tableNumber}`;
  let sessionId = sessionStorage.getItem(sessionStorageKey);
  
  // Check if sessionId in URL parameter (for direct links)
  const urlSessionId = params.get("session");
  if (urlSessionId) {
    sessionId = urlSessionId;
  }
  
  // Generate new session ID if not found
  if (!sessionId) {
    sessionId = generateId();
    sessionStorage.setItem(sessionStorageKey, sessionId);
  }
  
  state.sessionId = sessionId;
  document.getElementById("tableLabel").textContent = `Pesanan Meja ${state.tableNumber}`;
  document.title = `Menu – Meja ${state.tableNumber}`;
}

async function initFirebase() {
  try {
    const { initializeApp } = await import(
      "https://www.gstatic.com/firebasejs/10.14.0/firebase-app.js"
    );
    const { getFirestore, collection, getDocs, query, where, addDoc, serverTimestamp, onSnapshot, orderBy, doc, getDoc, runTransaction } = await import(
      "https://www.gstatic.com/firebasejs/10.14.0/firebase-firestore.js"
    );
    const app = initializeApp(FIREBASE_CONFIG);
    state.db = getFirestore(app);
    // Expose Firestore helpers on state for later use
    state._firestore = { collection, getDocs, query, where, addDoc, serverTimestamp, onSnapshot, orderBy, doc, getDoc, runTransaction };
  } catch (err) {
    console.error("Firebase init failed:", err);
    showError("Tidak dapat terhubung ke server.");
  }
}

// ---------------------------------------------------------------------------
// Load Settings from Firestore
// ---------------------------------------------------------------------------
async function loadSettings() {
  try {
    if (!state.db) return;
    const { doc, getDoc } = state._firestore;
    const settingsSnap = await getDoc(doc(state.db, "settings", "settings"));
    if (settingsSnap.exists()) {
      const data = settingsSnap.data();

      const businessName = (
        data.businessName ||
        data.business_name ||
        data.business ||
        data.name ||
        ""
      ).toString().trim();
      if (businessName) {
        state.businessName = businessName;
        const titleEl = document.querySelector(".header-title");
        if (titleEl) titleEl.textContent = businessName;
        document.title = `${businessName} – Meja ${state.tableNumber}`;
      }

      const logoUrl = (
        data.logoUrl ||
        data.logo_url ||
        data.businessLogoUrl ||
        data.iconUrl ||
        ""
      ).toString().trim();
      if (logoUrl) {
        const logoEl = document.getElementById("headerLogo");
        if (logoEl) {
          logoEl.textContent = "";
          const img = document.createElement("img");
          img.src = logoUrl;
          img.alt = businessName || "Logo";
          img.style.cssText = "width:36px;height:36px;object-fit:cover;border-radius:50%;";
          img.onerror = () => { logoEl.textContent = "🍜"; };
          logoEl.appendChild(img);
        }
      }

      // Which payment methods are enabled for customers (set by the seller
      // app in the settings document). Defaults to all enabled.
      const pm = data.paymentMethods || data.payment_methods || {};
      const anyEnabled = pm.QRIS !== false || pm.BANK_TRANSFER !== false || pm.CASHIER !== false;
      if (anyEnabled) {
        state.paymentMethodsEnabled = {
          QRIS:          pm.QRIS !== false,
          BANK_TRANSFER: pm.BANK_TRANSFER !== false,
          CASHIER:       pm.CASHIER !== false
        };
      }

      // Business location + order radius (set by the seller app). When both
      // are present, customers must be within the radius to place an order.
      const bizLat  = data.businessLatitude  ?? data.business_latitude  ?? null;
      const bizLng  = data.businessLongitude ?? data.business_longitude ?? null;
      if (bizLat != null && bizLng != null) {
        state.businessLocation = { latitude: Number(bizLat), longitude: Number(bizLng) };
      }
      const radius = data.orderRadiusMeters ?? data.order_radius_meters ?? null;
      if (radius != null) {
        state.orderRadiusMeters = Number(radius);
      }
    }
  } catch (err) {
    console.error("loadSettings error:", err);
  }
}

// ---------------------------------------------------------------------------
// Order type chooser + location/radius gating
//
// On first visit (per table+session) the customer picks DINE_IN or TAKE_AWAY
// before the menu is shown. The browser is asked for location permission so
// the order radius configured by the seller can be enforced: when the
// customer is outside the radius (or location is unavailable) no order can
// be placed.
// ---------------------------------------------------------------------------

function orderTypeKey() {
  return `culinary_order_type_${state.tableNumber}_${state.sessionId}`;
}

function showOrderTypeChooser() {
  const saved = sessionStorage.getItem(orderTypeKey());
  if (saved === "DINE_IN" || saved === "TAKE_AWAY") {
    state.orderType = saved;
  } else {
    const modal = document.getElementById("orderTypeModal");
    if (modal) {
      modal.style.display = "flex";
      document.body.style.overflow = "hidden";
    }
  }
  requestCustomerLocation();
}

function requestCustomerLocation() {
  const statusEl = document.getElementById("orderTypeStatus");
  const retryBtn = document.getElementById("orderTypeRetryBtn");

  if (!navigator.geolocation) {
    state.locationStatus = "denied";
    if (statusEl) statusEl.textContent = "Browser tidak mendukung izin lokasi. Tidak dapat memesan.";
    if (retryBtn) retryBtn.style.display = "none";
    disableOrderTypeButtons(true);
    updateLocationBanner();
    return;
  }

  state.locationStatus = "unknown";
  if (statusEl) statusEl.textContent = "Meminta izin lokasi...";
  if (retryBtn) retryBtn.style.display = "none";
  const distEl = document.getElementById("orderTypeDistance");
  if (distEl) distEl.textContent = "";
  disableOrderTypeButtons(true);

  navigator.geolocation.getCurrentPosition(
    (position) => {
      state.customerLocation = {
        latitude: position.coords.latitude,
        longitude: position.coords.longitude
      };
      state.locationStatus = "granted";
      resolveLocationAndEnable();
    },
    (err) => {
      state.locationStatus = "denied";
      state.customerLocation = null;
      state.customerDistanceMeters = null;
      if (statusEl) {
        statusEl.textContent = "Izin lokasi diperlukan untuk memesan. Aktifkan izin lokasi di browser, lalu coba lagi.";
      }
      if (retryBtn) retryBtn.style.display = "inline-block";
      disableOrderTypeButtons(true);
      updateLocationBanner();
    },
    { enableHighAccuracy: true, timeout: 10000, maximumAge: 60000 }
  );
}

function resolveLocationAndEnable() {
  const statusEl = document.getElementById("orderTypeStatus");
  const distEl = document.getElementById("orderTypeDistance");
  const retryBtn = document.getElementById("orderTypeRetryBtn");
  if (retryBtn) retryBtn.style.display = "none";

  const business = state.businessLocation;
  const radius = state.orderRadiusMeters;

  if (!business || !radius || radius <= 0) {
    // No radius configured yet — location gating disabled, allow ordering.
    if (statusEl) statusEl.textContent = "Pilih cara pemesanan:";
    if (distEl) distEl.textContent = "";
    disableOrderTypeButtons(false);
    updateLocationBanner();
    return;
  }

  const dist = haversineMeters(
    state.customerLocation.latitude, state.customerLocation.longitude,
    business.latitude, business.longitude
  );
  state.customerDistanceMeters = dist;

  if (statusEl) statusEl.textContent = "Lokasi Anda dalam area pesanan ✓";
  if (distEl) {
    distEl.textContent = `Jarak: ${formatDistance(dist)} · Radius: ${formatDistance(radius)}`;
  }

  if (dist > radius) {
    if (statusEl) statusEl.textContent = "Lokasi Anda di luar area pesanan. Tidak dapat memesan.";
    disableOrderTypeButtons(true);
  } else {
    disableOrderTypeButtons(false);
  }
  updateLocationBanner();
}

function chooseOrderType(type) {
  const business = state.businessLocation;
  const radius = state.orderRadiusMeters;

  if (business && radius && radius > 0) {
    if (state.locationStatus !== "granted" || !state.customerLocation) {
      alert("Izin lokasi diperlukan untuk memesan. Aktifkan izin lokasi lalu muat ulang halaman.");
      return;
    }
    const dist = haversineMeters(
      state.customerLocation.latitude, state.customerLocation.longitude,
      business.latitude, business.longitude
    );
    state.customerDistanceMeters = dist;
    if (dist > radius) {
      alert(`Lokasi Anda (${formatDistance(dist)}) di luar radius pesanan (${formatDistance(radius)}). Tidak dapat memesan.`);
      return;
    }
  }

  state.orderType = type;
  sessionStorage.setItem(orderTypeKey(), type);
  const modal = document.getElementById("orderTypeModal");
  if (modal) {
    modal.style.display = "none";
    document.body.style.overflow = "";
  }
}

function retryLocation() {
  state.customerLocation = null;
  state.customerDistanceMeters = null;
  state.locationStatus = "unknown";
  requestCustomerLocation();
}

function disableOrderTypeButtons(disabled) {
  document.querySelectorAll(".order-type-btn").forEach(btn => {
    btn.disabled = disabled;
  });
}

function haversineMeters(lat1, lng1, lat2, lng2) {
  const R = 6371000;
  const toRad = (d) => d * Math.PI / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}

function formatDistance(meters) {
  if (meters >= 1000) {
    const km = (meters / 1000).toFixed(1).replace(/\.0$/, "");
    return `${km} km`;
  }
  return `${Math.round(meters)} m`;
}

function updateLocationBanner() {
  const banner = document.getElementById("locationWarning");
  if (!banner) return;

  const business = state.businessLocation;
  const radius = state.orderRadiusMeters;
  let message = "";

  if (business && radius && radius > 0) {
    if (state.locationStatus !== "granted") {
      message = "Izin lokasi diperlukan untuk memesan. Aktifkan izin lokasi lalu muat ulang halaman.";
    } else if (state.customerDistanceMeters > radius) {
      message = `Lokasi Anda di luar radius pesanan (${formatDistance(state.customerDistanceMeters)} > ${formatDistance(radius)}). Tidak dapat memesan.`;
    }
  }

  banner.textContent = message;
  banner.style.display = message ? "flex" : "none";
}

// Returns { ok, message }. Blocks ordering when the customer is outside the
// configured radius or when their location cannot be verified.
function verifyRadiusForOrder() {
  const business = state.businessLocation;
  const radius = state.orderRadiusMeters;
  if (!business || !radius || radius <= 0) return { ok: true };

  if (state.locationStatus !== "granted" || !state.customerLocation) {
    return {
      ok: false,
      message: "Izin lokasi diperlukan untuk memesan. Aktifkan izin lokasi lalu muat ulang halaman."
    };
  }

  const dist = haversineMeters(
    state.customerLocation.latitude, state.customerLocation.longitude,
    business.latitude, business.longitude
  );
  state.customerDistanceMeters = dist;
  if (dist > radius) {
    return {
      ok: false,
      message: `Lokasi Anda (${formatDistance(dist)}) di luar radius pesanan (${formatDistance(radius)}). Tidak dapat memesan.`
    };
  }
  return { ok: true };
}

// ---------------------------------------------------------------------------
// Load Menu from Firestore
// ---------------------------------------------------------------------------
async function loadMenu() {
  showLoading();
  try {
    if (!state.db) throw new Error("Database tidak tersedia");
    const { collection, getDocs, query, where } = state._firestore;

    // Load categories
    const catSnap = await getDocs(collection(state.db, "categories"));
    state.categories = catSnap.docs
      .map(d => ({ id: d.id, ...d.data() }))
      .filter(c => c.isActive !== false)
      .sort((a, b) => (a.displayOrder || 0) - (b.displayOrder || 0));

    // Load menu items
    const menuSnap = await getDocs(collection(state.db, "menuItems"));
    state.menuItems = menuSnap.docs.map(d => ({ id: d.id, ...d.data() }));

    renderCategoryTabs();
    renderMenuGrid("all");
    loadCartFromStorage();
    showMenuContent();
  } catch (err) {
    console.error("loadMenu error:", err);
    showError(err.message || "Gagal memuat menu.");
  }
}

// ---------------------------------------------------------------------------
// Render

// ---------------------------------------------------------------------------
// Load Order History
// ---------------------------------------------------------------------------
async function loadOrderHistory() {
  try {
    if (!state.db) return;
    const { collection, query, where, onSnapshot, orderBy } = state._firestore;

    // Listen to orders for this session
    // Filter by both tableNumber and sessionId to support multiple customers at same table
    const ordersQuery = query(
      collection(state.db, "orders"),
      where("tableNumber", "==", state.tableNumber),
      where("sessionId", "==", state.sessionId),
      orderBy("createdAt", "desc")
    );

    // Clean up previous listener if exists
    if (state.ordersListener) {
      state.ordersListener();
    }

    // Set up real-time listener
    state.ordersListener = onSnapshot(ordersQuery, (snapshot) => {
      const now = new Date();
      const oneDayAgo = new Date(now.getTime() - 24 * 60 * 60 * 1000);
      
      state.orders = snapshot.docs
        .map(doc => ({
          id: doc.id,
          ...doc.data()
        }))
        .filter(order => {
          // Exclude cancelled orders
          if (order.status === "CANCELLED") return false;
          
          // Keep completed/served orders only if within last 24 hours
          if (order.status === "COMPLETED" || order.status === "SERVED") {
            const orderDate = order.updatedAt?.toDate ? order.updatedAt.toDate() : 
                            order.createdAt?.toDate ? order.createdAt.toDate() : new Date(0);
            return orderDate >= oneDayAgo;
          }
          
          // Keep all other statuses (PENDING, IN_QUEUE, PREPARING, READY)
          return true;
        });
      
      updateOrderHistoryUI();
    }, (error) => {
      console.error("Error listening to orders:", error);
    });
  } catch (err) {
    console.error("loadOrderHistory error:", err);
  }
}

function updateOrderHistoryUI() {
  const badge = document.getElementById("orderHistoryBadge");
  if (badge) {
    badge.textContent = state.orders.length;
    badge.style.display = state.orders.length > 0 ? "flex" : "none";
  }
}

// ---------------------------------------------------------------------------
function renderCategoryTabs() {
  const tabs = document.getElementById("categoryTabs");
  tabs.innerHTML = "";

  const allBtn = createTabButton("all", "Semua");
  tabs.appendChild(allBtn);

  state.categories.forEach(cat => {
    tabs.appendChild(createTabButton(cat.id, cat.name));
  });
}

function createTabButton(id, label) {
  const btn = document.createElement("button");
  btn.className = "tab-btn" + (id === state.activeCategory ? " active" : "");
  btn.textContent = label;
  btn.setAttribute("role", "tab");
  btn.setAttribute("aria-selected", id === state.activeCategory);
  btn.onclick = () => {
    state.activeCategory = id;
    document.querySelectorAll(".tab-btn").forEach(b => {
      b.classList.remove("active");
      b.setAttribute("aria-selected", "false");
    });
    btn.classList.add("active");
    btn.setAttribute("aria-selected", "true");
    renderMenuGrid(id);
  };
  return btn;
}

function renderMenuGrid(categoryId) {
  const grid = document.getElementById("menuGrid");
  const filtered = categoryId === "all"
    ? state.menuItems
    : state.menuItems.filter(m => m.categoryId === categoryId);

  if (filtered.length === 0) {
    grid.innerHTML = `<p style="grid-column:1/-1;text-align:center;color:var(--text-muted);padding:40px 0">
      Tidak ada menu di kategori ini.</p>`;
    return;
  }

  grid.innerHTML = filtered.map(item => renderMenuCard(item)).join("");
}

function renderMenuCard(item) {
  const price = formatRupiah(item.price || 0);
  const imgHtml = item.imageUrl
    ? `<img class="menu-card-image" src="${escHtml(item.imageUrl)}" alt="${escHtml(item.name)}" loading="lazy"/>`
    : `<div class="menu-card-image-placeholder">🍽️</div>`;

  // Check availability: must be available AND have stock (if stock is tracked)
  const hasStock = item.stock === null || item.stock === undefined || item.stock > 0;
  const isAvailable = item.available && hasStock;
  
  const unavailableClass = !isAvailable ? " menu-card-unavailable" : "";
  const actionHtml = isAvailable
    ? `<button class="menu-card-add" onclick="event.stopPropagation();quickAddToCart('${item.id}')" aria-label="Tambah ${escHtml(item.name)}">+</button>`
    : `<span class="menu-card-badge-unavailable">Habis</span>`;

  return `
    <div class="menu-card${unavailableClass}" onclick="${isAvailable ? `openItemModal('${item.id}')` : ''}" role="button" tabindex="${isAvailable ? 0 : -1}">
      ${imgHtml}
      <div class="menu-card-body">
        <div class="menu-card-name">${escHtml(item.name)}</div>
        ${item.description ? `<div class="menu-card-desc">${escHtml(item.description)}</div>` : ""}
        <div class="menu-card-footer">
          <span class="menu-card-price">${price}</span>
          ${actionHtml}
        </div>
      </div>
    </div>`;
}

// ---------------------------------------------------------------------------
// Item Detail Modal
// ---------------------------------------------------------------------------
function openItemModal(itemId) {
  const item = state.menuItems.find(m => m.id === itemId);
  if (!item) return;

  const modal = document.getElementById("itemModal");
  const content = document.getElementById("itemModalContent");

  const imgHtml = item.imageUrl
    ? `<img class="modal-image" src="${escHtml(item.imageUrl)}" alt="${escHtml(item.name)}" />`
    : `<div class="modal-image-placeholder">🍽️</div>`;

  let toppingGroupsHtml = "";
  if (Array.isArray(item.toppingGroups)) {
    item.toppingGroups.forEach((group, gi) => {
      const hint = group.isRequired
        ? (group.type === "SINGLE_SELECT" ? "Wajib pilih salah satu" : "Wajib pilih minimal satu")
        : (group.type === "SINGLE_SELECT" ? "Pilih salah satu (opsional)" : "Pilih beberapa (opsional)");

      const toppingItems = (group.toppings || []).map((t, ti) => {
        const priceText = t.additionalPrice > 0 ? ` +${formatRupiah(t.additionalPrice)}` : "";
        const inputType = group.type === "SINGLE_SELECT" ? "radio" : "checkbox";
        return `
          <label class="topping-item" for="top_${gi}_${ti}">
            <div class="topping-item-label">
              <input type="${inputType}" name="tg_${gi}" id="top_${gi}_${ti}"
                data-group-id="${escHtml(group.id)}" data-topping-id="${escHtml(t.id)}"
                data-topping-name="${escHtml(t.name)}" data-topping-price="${t.additionalPrice || 0}"
                onchange="onToppingChange(this)" />
              ${escHtml(t.name)}
            </div>
            ${priceText ? `<span class="topping-item-price">${priceText}</span>` : ""}
          </label>`;
      }).join("");

      toppingGroupsHtml += `
        <div class="topping-group">
          <div class="topping-group-name">${escHtml(group.name)}</div>
          <div class="topping-group-hint">${hint}</div>
          <div class="topping-list">${toppingItems}</div>
        </div>`;
    });
  }

  content.innerHTML = `
    ${imgHtml}
    <h2 class="modal-name">${escHtml(item.name)}</h2>
    <div class="modal-price">${formatRupiah(item.price || 0)}</div>
    ${item.description ? `<p class="modal-desc">${escHtml(item.description)}</p>` : ""}
    ${toppingGroupsHtml}
    <div class="modal-notes form-section">
      <label class="form-label" for="itemNotes">Catatan</label>
      <textarea id="itemNotes" class="form-input form-textarea" placeholder="Contoh: tidak pakai bawang..."></textarea>
    </div>
    <div class="modal-qty-row">
      <strong>Jumlah</strong>
      <div class="qty-control">
        <button class="qty-btn" onclick="changeModalQty(-1)">−</button>
        <span class="qty-value" id="modalQty">1</span>
        <button class="qty-btn" onclick="changeModalQty(1)">+</button>
      </div>
    </div>
    <button class="btn-primary modal-add-btn" id="modalAddBtn" onclick="addFromModal('${itemId}')">
      Tambah ke Keranjang
    </button>`;

  modal.style.display = "flex";
  document.body.style.overflow = "hidden";

  modal.onclick = (e) => { if (e.target === modal) closeItemModal(); };
}

function closeItemModal() {
  document.getElementById("itemModal").style.display = "none";
  document.body.style.overflow = "";
}

function changeModalQty(delta) {
  const el = document.getElementById("modalQty");
  const current = parseInt(el.textContent, 10);
  el.textContent = Math.max(1, current + delta);
}

function onToppingChange(input) {
  const label = input.closest(".topping-item");
  if (input.type === "radio") {
    document.querySelectorAll(`input[name="${input.name}"]`).forEach(r => {
      r.closest(".topping-item").classList.remove("selected");
    });
  }
  label.classList.toggle("selected", input.checked);
}

function addFromModal(itemId) {
  const item = state.menuItems.find(m => m.id === itemId);
  if (!item) return;

  const qty = parseInt(document.getElementById("modalQty").textContent, 10);
  const notes = document.getElementById("itemNotes").value.trim();

  // Collect selected toppings
  const selectedToppings = [];
  document.querySelectorAll("#itemModalContent input[type=radio]:checked, #itemModalContent input[type=checkbox]:checked")
    .forEach(input => {
      selectedToppings.push({
        toppingId:      input.dataset.toppingId,
        toppingGroupId: input.dataset.groupId,
        name:           input.dataset.toppingName,
        additionalPrice: parseInt(input.dataset.toppingPrice, 10) || 0
      });
    });

  addToCart(item, qty, selectedToppings, notes);
  closeItemModal();
}

function quickAddToCart(itemId) {
  const item = state.menuItems.find(m => m.id === itemId);
  if (!item) return;
  if (Array.isArray(item.toppingGroups) && item.toppingGroups.length > 0) {
    openItemModal(itemId);
    return;
  }
  addToCart(item, 1, [], "");
}

// ---------------------------------------------------------------------------
// Cart
// ---------------------------------------------------------------------------
function addToCart(item, quantity, selectedToppings, notes) {
  const toppingsTotal = selectedToppings.reduce((s, t) => s + (t.additionalPrice || 0), 0);
  const subtotal = ((item.price || 0) + toppingsTotal) * quantity;

  // Try to merge with existing cart line if same item + same toppings + same notes
  const key = `${item.id}_${JSON.stringify(selectedToppings)}_${notes}`;
  const existing = state.cart.find(c => c.key === key);
  if (existing && selectedToppings.length === 0) {
    existing.quantity += quantity;
    existing.subtotal = ((item.price || 0) + toppingsTotal) * existing.quantity;
  } else {
    state.cart.push({ key, menuItem: item, quantity, selectedToppings, notes, subtotal });
  }

  saveCartToStorage();
  updateCartUI();
  showCartToast(item.name);
}

function removeFromCart(key) {
  state.cart = state.cart.filter(c => c.key !== key);
  saveCartToStorage();
  updateCartUI();
  renderCartBody();
}

function changeCartQty(key, delta) {
  const item = state.cart.find(c => c.key === key);
  if (!item) return;
  const toppingsTotal = item.selectedToppings.reduce((s, t) => s + (t.additionalPrice || 0), 0);
  item.quantity = Math.max(0, item.quantity + delta);
  if (item.quantity === 0) {
    removeFromCart(key);
    return;
  }
  item.subtotal = ((item.menuItem.price || 0) + toppingsTotal) * item.quantity;
  saveCartToStorage();
  updateCartUI();
  renderCartBody();
}

function updateCartUI() {
  const totalQty = state.cart.reduce((s, c) => s + c.quantity, 0);
  const badge = document.getElementById("cartBadge");
  badge.textContent = totalQty;
  badge.style.display = totalQty > 0 ? "flex" : "none";
}

function renderCartBody() {
  const body = document.getElementById("cartBody");
  const footer = document.getElementById("cartFooter");

  if (state.cart.length === 0) {
    body.innerHTML = `<p class="cart-empty">Keranjang kosong</p>`;
    footer.style.display = "none";
    return;
  }

  body.innerHTML = state.cart.map(c => {
    const toppingText = c.selectedToppings.map(t => t.name).join(", ");
    return `
      <div class="cart-item">
        <div class="cart-item-info">
          <div class="cart-item-name">${escHtml(c.menuItem.name)}</div>
          ${toppingText ? `<div class="cart-item-toppings">${escHtml(toppingText)}</div>` : ""}
          ${c.notes ? `<div class="cart-item-toppings">Catatan: ${escHtml(c.notes)}</div>` : ""}
          <div class="qty-control">
            <button class="qty-btn" onclick="changeCartQty('${c.key}', -1)">−</button>
            <span class="qty-value">${c.quantity}</span>
            <button class="qty-btn" onclick="changeCartQty('${c.key}', 1)">+</button>
          </div>
        </div>
        <div class="cart-item-price">${formatRupiah(c.subtotal)}</div>
      </div>`;
  }).join("");

  const total = state.cart.reduce((s, c) => s + c.subtotal, 0);
  document.getElementById("cartTotal").textContent = formatRupiah(total);
  footer.style.display = "block";
}

function toggleCart() {
  const drawer = document.getElementById("cartDrawer");
  const overlay = document.getElementById("overlay");
  const isOpen = drawer.classList.contains("open");
  if (isOpen) {
    closeCart();
  } else {
    renderCartBody();
    drawer.classList.add("open");
    overlay.style.display = "block";
    document.body.style.overflow = "hidden";
  }
}

function closeCart() {
  document.getElementById("cartDrawer").classList.remove("open");
  document.getElementById("overlay").style.display = "none";
  document.body.style.overflow = "";
}

// ---------------------------------------------------------------------------
// Order History
// ---------------------------------------------------------------------------
function toggleOrderHistory() {
  const drawer = document.getElementById("orderHistoryDrawer");
  const overlay = document.getElementById("overlay");
  const isOpen = drawer.classList.contains("open");
  if (isOpen) {
    closeOrderHistory();
  } else {
    renderOrderHistory();
    drawer.classList.add("open");
    overlay.style.display = "block";
    document.body.style.overflow = "hidden";
  }
}

function closeOrderHistory() {
  document.getElementById("orderHistoryDrawer").classList.remove("open");
  document.getElementById("overlay").style.display = "none";
  document.body.style.overflow = "";
}

function renderOrderHistory() {
  const body = document.getElementById("orderHistoryBody");
  
  if (state.orders.length === 0) {
    body.innerHTML = `<p class="cart-empty">Belum ada pesanan</p>`;
    return;
  }

  body.innerHTML = state.orders.map(order => {
    const statusLabels = {
      PENDING: { text: "Menunggu Konfirmasi", color: "#FF9800" },
      IN_QUEUE: { text: "Dalam Antrian", color: "#FF9800" },
      PREPARING: { text: "Sedang Disiapkan", color: "#2196F3" },
      READY: { text: "Siap Diambil", color: "#4CAF50" },
      SERVED: { text: "Sudah Disajikan", color: "#9E9E9E" },
      COMPLETED: { text: "Selesai", color: "#9E9E9E" },
      CANCELLED: { text: "Dibatalkan", color: "#F44336" }
    };
    
    const status = statusLabels[order.status] || statusLabels.PENDING;
    const orderDate = order.createdAt?.toDate ? order.createdAt.toDate() : new Date();
    const timeStr = orderDate.toLocaleTimeString("id-ID", { hour: "2-digit", minute: "2-digit" });
    
    const itemsList = (order.items || []).map(item => 
      `<div style="font-size: 14px; color: #666; margin-top: 4px;">
        ${item.quantity}× ${escHtml(item.menuItemName)}
      </div>`
    ).join("");
    
    const totalAmount = (order.items || []).reduce((sum, item) => {
      const toppingTotal = (item.selectedToppings || []).reduce((s, t) => s + (t.additionalPrice || 0), 0);
      return sum + ((item.unitPrice + toppingTotal) * item.quantity);
    }, 0);
    
    return `
      <div class="cart-item" style="border-left: 3px solid ${status.color}; cursor: pointer;" onclick="openOrderDetailModal('${escHtml(order.id)}')">
        <div class="cart-item-info">
          <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
            <div class="cart-item-name">#${escHtml(order.id.slice(-6).toUpperCase())}</div>
            <span style="background: ${status.color}; color: white; padding: 4px 12px; border-radius: 12px; font-size: 12px; font-weight: 500;">
              ${status.text}
            </span>
          </div>
          <div style="font-size: 13px; color: #999; margin-bottom: 8px;">${timeStr}</div>
          ${itemsList}
          ${order.estimatedReadyMinutes ? `<div style="font-size: 13px; color: #FF9800; margin-top: 8px; font-weight: 500;">⏱️ Estimasi ${order.estimatedReadyMinutes} menit</div>` : ""}
        </div>
        <div class="cart-item-price">${formatRupiah(totalAmount)}</div>
      </div>`;
  }).join("");
}

function openOrderDetailModal(orderId) {
  const order = state.orders.find(o => o.id === orderId);
  if (!order) return;

  const modal = document.getElementById("itemModal");
  const content = document.getElementById("itemModalContent");

  const statusLabels = {
   PENDING: { text: "Menunggu Konfirmasi", color: "#FF9800" },
   IN_QUEUE: { text: "Dalam Antrian", color: "#FF9800" },
   PREPARING: { text: "Sedang Disiapkan", color: "#2196F3" },
   READY: { text: "Siap Diambil", color: "#4CAF50" },
   SERVED: { text: "Sudah Disajikan", color: "#9E9E9E" },
   COMPLETED: { text: "Selesai", color: "#9E9E9E" },
   CANCELLED: { text: "Dibatalkan", color: "#F44336" }
  };

  const status = statusLabels[order.status] || statusLabels.PENDING;
  const orderDate = order.createdAt?.toDate ? order.createdAt.toDate() : new Date();
  const dateStr = orderDate.toLocaleDateString("id-ID", { year: "numeric", month: "long", day: "2-digit" });
  const timeStr = orderDate.toLocaleTimeString("id-ID", { hour: "2-digit", minute: "2-digit" });

  const itemsList = (order.items || []).map(item => {
   const toppingText = (item.selectedToppings || []).length > 0
     ? ` + ${(item.selectedToppings || []).map(t => t.name).join(", ")}`
     : "";
   const itemTotal = ((item.unitPrice + ((item.selectedToppings || []).reduce((s, t) => s + (t.additionalPrice || 0), 0))) * item.quantity);
   return `
     <div style="padding: 12px 0; border-bottom: 1px solid #e0e0e0;">
       <div style="display: flex; justify-content: space-between; align-items: start; margin-bottom: 4px;">
         <div>
           <div style="font-weight: 600; font-size: 14px;">${escHtml(item.menuItemName)}</div>
           ${toppingText ? `<div style="font-size: 12px; color: #999; margin-top: 2px;">Tambahan: ${escHtml(toppingText)}</div>` : ""}
         </div>
         <div style="font-weight: 600; color: var(--primary);">${formatRupiah(itemTotal)}</div>
       </div>
       <div style="font-size: 12px; color: #999;">Jumlah: ${item.quantity}</div>
     </div>`;
  }).join("");

  const totalAmount = (order.items || []).reduce((sum, item) => {
   const toppingTotal = (item.selectedToppings || []).reduce((s, t) => s + (t.additionalPrice || 0), 0);
   return sum + ((item.unitPrice + toppingTotal) * item.quantity);
  }, 0);

  content.innerHTML = `
   <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
     <h2 class="modal-name" style="margin-bottom: 0;">#${escHtml(order.id.slice(-6).toUpperCase())}</h2>
     <span style="background: ${status.color}; color: white; padding: 6px 14px; border-radius: 12px; font-size: 12px; font-weight: 500;">
       ${status.text}
     </span>
   </div>
   <div style="font-size: 13px; color: #999; margin-bottom: 16px;">
     <div>${dateStr}</div>
     <div>${timeStr}</div>
   </div>
   <div style="background: #f9f9f9; padding: 12px; border-radius: 8px; margin-bottom: 16px;">
     <div style="font-size: 12px; font-weight: 600; color: #999; text-transform: uppercase; margin-bottom: 8px;">Item Pesanan</div>
     ${itemsList}
   </div>
   <div style="background: var(--surface-2); padding: 12px; border-radius: 8px; margin-bottom: 16px;">
     <div style="display: flex; justify-content: space-between; margin-bottom: 8px;">
       <span>Subtotal</span>
       <span>${formatRupiah(totalAmount)}</span>
     </div>
     <div style="display: flex; justify-content: space-between; font-weight: 600; font-size: 16px; color: var(--primary);">
       <span>Total</span>
       <span>${formatRupiah(totalAmount)}</span>
     </div>
   </div>
   ${order.notes ? `
     <div style="background: #fafafa; padding: 12px; border-radius: 8px; margin-bottom: 16px;">
       <div style="font-size: 12px; font-weight: 600; color: #999; text-transform: uppercase; margin-bottom: 4px;">Catatan</div>
       <div style="font-size: 14px; color: #666;">${escHtml(order.notes)}</div>
     </div>
   ` : ""}
   ${order.estimatedReadyMinutes ? `
     <div style="background: #fff3e0; padding: 12px; border-radius: 8px; margin-bottom: 16px;">
       <div style="font-size: 13px; color: #ff9800; font-weight: 500;">⏱️ Estimasi siap dalam ${order.estimatedReadyMinutes} menit</div>
     </div>
   ` : ""}`;

  modal.style.display = "flex";
  document.body.style.overflow = "hidden";

  modal.onclick = (e) => { if (e.target === modal) closeOrderDetailModal(); };
}

function closeOrderDetailModal() {
  document.getElementById("itemModal").style.display = "none";
  document.body.style.overflow = "";
}

// ---------------------------------------------------------------------------
// Checkout
// ---------------------------------------------------------------------------
function goToCheckout() {
  if (state.cart.length === 0) return;
  closeCart();

  // Reset the submit button in case a previous order left it disabled
  // with "Memproses..." (placeOrder only restores it on failure).
  const placeOrderBtn = document.getElementById("placeOrderBtn");
  placeOrderBtn.disabled = false;
  placeOrderBtn.textContent = "Pesan Sekarang";

  // Show only the payment methods enabled in settings. If the currently
  // selected method has been disabled, fall back to the first enabled one.
  const paymentMethods = [
    { value: "QRIS",          labelId: "paymentOptionQRIS" },
    { value: "BANK_TRANSFER", labelId: "paymentOptionBANK_TRANSFER" },
    { value: "CASHIER",       labelId: "paymentOptionCASHIER" }
  ];
  for (const method of paymentMethods) {
    const optionEl = document.getElementById(method.labelId);
    if (optionEl) {
      optionEl.style.display = state.paymentMethodsEnabled[method.value] ? "" : "none";
    }
  }
  const checkedMethod = document.querySelector("input[name='payment']:checked");
  if (checkedMethod && !state.paymentMethodsEnabled[checkedMethod.value]) {
    const firstEnabled = paymentMethods.find(m => state.paymentMethodsEnabled[m.value]);
    if (firstEnabled) {
      const fallbackRadio = document.querySelector(`input[name='payment'][value='${firstEnabled.value}']`);
      if (fallbackRadio) fallbackRadio.checked = true;
    }
  }

  const items = state.cart.map(c => `
    <div class="checkout-item">
      <div class="checkout-item-name">${c.quantity}× ${escHtml(c.menuItem.name)}</div>
      <div class="checkout-item-price">${formatRupiah(c.subtotal)}</div>
    </div>`).join("");

  document.getElementById("checkoutItems").innerHTML = items;
  const total = state.cart.reduce((s, c) => s + c.subtotal, 0);
  document.getElementById("checkoutSubtotal").textContent = formatRupiah(total);
  document.getElementById("checkoutTotal").textContent = formatRupiah(total);

  document.getElementById("tableLabel").textContent = `Checkout Meja ${state.tableNumber}`;
  document.getElementById("checkoutPage").style.display = "block";
  document.body.style.overflow = "hidden";
}

function closeCheckout() {
  document.getElementById("checkoutPage").style.display = "none";
  document.getElementById("tableLabel").textContent = `Pesanan Meja ${state.tableNumber}`;
  document.body.style.overflow = "";
}

async function placeOrder() {
  const btn = document.getElementById("placeOrderBtn");

  // Nama Pelanggan is mandatory before checkout can proceed.
  const customerName = document.getElementById("customerName").value.trim();
  if (!customerName) {
    btn.disabled = false;
    btn.textContent = "Pesan Sekarang";
    document.getElementById("customerName").focus();
    alert("Nama Pelanggan wajib diisi sebelum checkout.");
    return;
  }

  // Enforce the business order radius before allowing any order.
  const radiusCheck = verifyRadiusForOrder();
  if (!radiusCheck.ok) {
    btn.disabled = false;
    btn.textContent = "Pesan Sekarang";
    alert(radiusCheck.message);
    updateLocationBanner();
    return;
  }

  btn.disabled = true;
  btn.textContent = "Memproses...";

  let reservedLock = null; // { amount, orderId } — released on failure

  try {
    const notes = document.getElementById("orderNotes").value.trim();
    const paymentMethod = document.querySelector("input[name='payment']:checked")?.value || "CASHIER";

    const orderItems = state.cart.map(c => ({
      id:              generateId(),
      menuItemId:      c.menuItem.id,
      menuItemName:    c.menuItem.name,
      quantity:        c.quantity,
      unitPrice:       c.menuItem.price || 0,
      selectedToppings: c.selectedToppings,
      notes:           c.notes
    }));

    // Generate the order ID up-front so QRIS amount locks (which need to
    // reference an orderId) can be reserved before the order document
    // itself is created inside the stock transaction below.
    const orderId = generateId();

    const order = {
      tableNumber:   state.tableNumber,
      sessionId:     state.sessionId,    // Unique session ID to identify this customer
      customerName:  customerName,
      items:         orderItems,
      status:        "PENDING",
      orderType:     state.orderType || "DINE_IN",
      paymentMethod: paymentMethod,
      paymentStatus: "PENDING",
      notes:         notes,
      createdAt:     state._firestore.serverTimestamp(),
      updatedAt:     state._firestore.serverTimestamp(),
      estimatedReadyMinutes: 15
    };

    if (paymentMethod === "QRIS") {
      const baseAmount = state.cart.reduce((s, c) => s + c.subtotal, 0);
      const reservation = await reserveQrisAmount(baseAmount, orderId, state.tableNumber);
      reservedLock = { amount: reservation.finalAmount, orderId };

      order.qrisBaseAmount = baseAmount;
      order.qrisAmount = reservation.finalAmount;
      order.qrisFeeSteps = reservation.feeSteps;
      order.paymentLockExpiresAt = reservation.expiresAt;
    }

    // Import transaction functions
    const { runTransaction, doc } = await import(
      "https://www.gstatic.com/firebasejs/10.14.0/firebase-firestore.js"
    );

    // Use transaction to check and update stock atomically
    await runTransaction(state.db, async (transaction) => {
      // Phase 1: Read all menu items first (Firestore requires all reads before all writes)
      const stockUpdates = [];
      for (const item of orderItems) {
        const menuItemRef = doc(state.db, "menuItems", item.menuItemId);
        const menuItemDoc = await transaction.get(menuItemRef);

        if (!menuItemDoc.exists()) {
          throw new Error(`Menu item ${item.menuItemName} tidak ditemukan`);
        }

        const menuData = menuItemDoc.data();
        const currentStock = menuData.stock;

        // If stock is tracked (not null/undefined), check and decrease it
        if (currentStock !== null && currentStock !== undefined) {
          if (currentStock < item.quantity) {
            throw new Error(`Stok ${item.menuItemName} tidak cukup. Tersedia: ${currentStock}, Diminta: ${item.quantity}`);
          }

          stockUpdates.push({
            ref: menuItemRef,
            newStock: currentStock - item.quantity
          });
        }
      }

      // Phase 2: All writes after all reads
      for (const update of stockUpdates) {
        transaction.update(update.ref, { stock: update.newStock });
      }

      // Create the order using the pre-generated ID
      const orderRef = doc(state.db, "orders", orderId);
      transaction.set(orderRef, order);
    });

    showSuccess(orderId, state.tableNumber, paymentMethod, order.qrisAmount, order.paymentLockExpiresAt);
  } catch (err) {
    console.error("placeOrder error:", err);
    alert(err.message || "Gagal mengirim pesanan. Coba lagi.");
    btn.disabled = false;
    btn.textContent = "Pesan Sekarang";

    // If we reserved a QRIS amount lock but the order itself failed to
    // create, release it immediately instead of waiting 5 minutes so the
    // amount isn't needlessly held.
    if (reservedLock) {
      await releaseQrisAmountLock(reservedLock.amount, reservedLock.orderId);
    }
  }
}

// ---------------------------------------------------------------------------
// QRIS Amount Locking
//
// Since the DSP QRIS gateway transactions don't reliably carry a partner
// reference number, payments are matched to orders purely by amount. To
// avoid ambiguity between two simultaneously pending QRIS orders that would
// otherwise share the same total, each order reserves a *unique* amount for
// a limited time (QRIS_CONFIG.lockDurationMs, default 5 minutes) in the
// `qrisAmountLocks` Firestore collection. If the exact base amount is
// already locked by another still-pending order, a small qris_fee
// (QRIS_CONFIG.feeAmount) is added repeatedly until a free amount is found.
// ---------------------------------------------------------------------------

/**
 * Attempt to atomically reserve a unique QRIS amount for the given order.
 * @param {number} baseAmount - the order's natural total (before any fee)
 * @param {string} orderId - the pre-generated order ID this lock belongs to
 * @param {string} tableNumber
 * @returns {Promise<{finalAmount:number, feeSteps:number, expiresAt:Date}>}
 */
async function reserveQrisAmount(baseAmount, orderId, tableNumber) {
  const { doc, runTransaction, serverTimestamp } = state._firestore;
  const qrisConfig = window.QRIS_CONFIG || {};
  const feeAmount = qrisConfig.feeAmount ?? 1;
  const lockDurationMs = qrisConfig.lockDurationMs ?? 300000;
  const maxAttempts = qrisConfig.maxReservationAttempts ?? 50;

  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    const candidateAmount = baseAmount + attempt * feeAmount;
    const lockRef = doc(state.db, "qrisAmountLocks", String(candidateAmount));
    const expiresAt = new Date(Date.now() + lockDurationMs);

    try {
      const claimed = await runTransaction(state.db, async (transaction) => {
        const lockSnap = await transaction.get(lockRef);

        if (lockSnap.exists()) {
          const lockData = lockSnap.data();
          const stillLocked =
            lockData.status === "LOCKED" &&
            lockData.expiresAt &&
            lockData.expiresAt.toMillis() > Date.now();

          if (stillLocked) {
            return false; // Someone else's active lock; try the next amount
          }
        }

        transaction.set(lockRef, {
          amount: candidateAmount,
          orderId,
          tableNumber,
          status: "LOCKED",
          lockedAt: serverTimestamp(),
          expiresAt
        });
        return true;
      });

      if (claimed) {
        return {
          finalAmount: candidateAmount,
          feeSteps: attempt * feeAmount,
          expiresAt
        };
      }
    } catch (err) {
      console.error(`Failed to claim QRIS amount ${candidateAmount}:`, err);
      // Continue trying subsequent amounts rather than failing outright
    }
  }

  throw new Error("Gagal mengalokasikan nominal QRIS. Silakan coba lagi.");
}

/**
 * Release a QRIS amount lock early (e.g. if order creation failed after
 * the amount was already reserved). Only releases the lock if it still
 * belongs to this order, to avoid interfering with a newer reservation.
 */
async function releaseQrisAmountLock(amount, orderId) {
  try {
    const { doc, runTransaction } = state._firestore;
    const lockRef = doc(state.db, "qrisAmountLocks", String(amount));
    await runTransaction(state.db, async (transaction) => {
      const lockSnap = await transaction.get(lockRef);
      if (lockSnap.exists() && lockSnap.data().orderId === orderId) {
        transaction.update(lockRef, { status: "RELEASED", releasedReason: "ORDER_CREATE_FAILED" });
      }
    });
  } catch (err) {
    console.error("Failed to release QRIS amount lock:", err);
  }
}



// ---------------------------------------------------------------------------
// Success
// ---------------------------------------------------------------------------
function showSuccess(orderId, tableNumber, paymentMethod, qrisAmount, paymentLockExpiresAt) {
  const paymentLabels = {
    QRIS: "QRIS",
    BANK_TRANSFER: "Transfer Bank",
    CASHIER: "Bayar di Kasir"
  };
  const orderTypeLabels = {
    DINE_IN: "Makan di Tempat",
    TAKE_AWAY: "Dibawa Pulang"
  };

  document.getElementById("successOrderInfo").innerHTML = `
    <div class="order-info-row"><span>No. Pesanan</span><strong>#${escHtml(orderId.slice(-6).toUpperCase())}</strong></div>
    <div class="order-info-row"><span>Meja</span><strong>${escHtml(String(tableNumber))}</strong></div>
    <div class="order-info-row"><span>Tipe</span><strong>${escHtml(orderTypeLabels[state.orderType] || "Makan di Tempat")}</strong></div>
    <div class="order-info-row"><span>Pembayaran</span><strong>${escHtml(paymentLabels[paymentMethod] || paymentMethod)}</strong></div>
  `;

  document.getElementById("checkoutPage").style.display = "none";
  document.getElementById("tableLabel").textContent = `Pesanan Meja ${state.tableNumber}`;
  document.getElementById("successPage").style.display = "block";
  
  // Handle QRIS payment display and polling
  if (paymentMethod === "QRIS") {
    handleQrisPayment(orderId, qrisAmount, paymentLockExpiresAt);
  } else {
    // Hide QRIS section for non-QRIS payments
    document.getElementById("qrisPaymentSection").style.display = "none";
  }
  
  state.cart = [];
  saveCartToStorage();
  updateCartUI();
  // Order history will automatically update via real-time listener
}

function resetApp() {
  document.getElementById("successPage").style.display = "none";
  document.body.style.overflow = "";
  document.getElementById("customerName").value = "";
  document.getElementById("orderNotes").value = "";

  if (state.qrisPollingTimer) {
    clearInterval(state.qrisPollingTimer);
    state.qrisPollingTimer = null;
  }
  if (state.qrisCountdownTimer) {
    clearInterval(state.qrisCountdownTimer);
    state.qrisCountdownTimer = null;
  }
}

// ---------------------------------------------------------------------------
// UI helpers
// ---------------------------------------------------------------------------
function showLoading() {
  document.getElementById("loadingState").style.display = "flex";
  document.getElementById("errorState").style.display = "none";
  document.getElementById("menuContent").style.display = "none";
}

function showError(message) {
  document.getElementById("loadingState").style.display = "none";
  document.getElementById("errorState").style.display = "flex";
  document.getElementById("menuContent").style.display = "none";
  document.getElementById("errorMessage").textContent = message;
}

function showMenuContent() {
  document.getElementById("loadingState").style.display = "none";
  document.getElementById("errorState").style.display = "none";
  document.getElementById("menuContent").style.display = "block";
}

function showCartToast(itemName) {
  const existing = document.getElementById("cartToast");
  if (existing) existing.remove();

  const toast = document.createElement("div");
  toast.id = "cartToast";
  toast.style.cssText = `
    position:fixed;bottom:20px;left:50%;transform:translateX(-50%);
    background:#323232;color:#fff;padding:10px 20px;border-radius:20px;
    font-size:14px;z-index:9999;animation:fadeInOut 2.5s ease forwards;
    white-space:nowrap;max-width:90vw;text-align:center;
  `;
  toast.textContent = `${itemName} ditambahkan ke keranjang`;

  const style = document.createElement("style");
  style.textContent = `@keyframes fadeInOut {
    0%{opacity:0;transform:translateX(-50%) translateY(10px)}
    15%{opacity:1;transform:translateX(-50%) translateY(0)}
    75%{opacity:1}
    100%{opacity:0;transform:translateX(-50%) translateY(10px)}
  }`;
  document.head.appendChild(style);
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 2600);
}

// ---------------------------------------------------------------------------
// Cart persistence (localStorage)
// ---------------------------------------------------------------------------
function cartStorageKey() {
  return `culinary_cart_${state.tableNumber}_${state.sessionId}`;
}

function saveCartToStorage() {
  try {
    localStorage.setItem(cartStorageKey(), JSON.stringify(state.cart));
  } catch (e) {
    console.warn("Failed to save cart to localStorage:", e);
  }
}

function loadCartFromStorage() {
  try {
    const saved = localStorage.getItem(cartStorageKey());
    if (!saved) return;
    const cart = JSON.parse(saved);
    // Re-hydrate menuItem references from the current menu and drop stale entries
    state.cart = cart.filter(c => {
      const menuItem = state.menuItems.find(m => m.id === c.menuItem?.id);
      if (!menuItem) return false;
      c.menuItem = menuItem;
      return true;
    });
    updateCartUI();
  } catch (e) {
    console.warn("Failed to load cart from localStorage:", e);
  }
}

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------
function formatRupiah(amount) {
  return "Rp " + Number(amount).toLocaleString("id-ID");
}

function escHtml(str) {
  if (!str) return "";
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function generateId() {
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

// ---------------------------------------------------------------------------
// QRIS Payment Handling
// ---------------------------------------------------------------------------
/**
 * Handle QRIS payment after order placement
 * Generates QRIS QR code (using the pre-reserved locked amount) and starts
 * polling for payment status.
 */
function handleQrisPayment(orderId, qrisAmount, paymentLockExpiresAt) {
  // Display QRIS payment section
  document.getElementById("qrisPaymentSection").style.display = "block";
  document.getElementById("successMessage").textContent = "Pesanan Anda telah diterima. Silakan lakukan pembayaran melalui QRIS.";
  
  // Store current order ID for polling
  state.currentPaymentOrderId = orderId;
  
  try {
    // Generate QRIS payment data using the amount locked for this order
    // (may include a small qris_fee adjustment if the base amount was
    // already reserved by another pending order — see reserveQrisAmount()).
    const qrisPayment = generateQrisPayment(
      qrisAmount,
      orderId.slice(-25)  // Use last 25 chars of order ID as reference
    );
    
    if (qrisPayment) {
      // Display QR code
      const qrWrapper = document.getElementById("qrisQrWrapper");
      const qrCode = document.getElementById("qrisQrCode");
      qrCode.src = qrisPayment.qrCodeImage;
      qrWrapper.style.display = "flex";
      
      // Start the 5-minute amount-lock countdown display
      startLockCountdown(paymentLockExpiresAt);

      // Start polling for payment status
      startPaymentPolling(orderId);
    } else {
      showQrisError("Gagal membuat QR code QRIS. Silakan coba lagi atau gunakan metode pembayaran lain.");
    }
  } catch (error) {
    console.error("Error handling QRIS payment:", error);
    showQrisError("Terjadi kesalahan saat memproses pembayaran QRIS. " + error.message);
  }
}

/**
 * Displays a live countdown until the reserved QRIS amount's lock expires.
 * This is informational only — the order itself remains PENDING and can
 * still be matched by amount after the lock expires, as long as the amount
 * hasn't been reused by a newer order in the meantime.
 */
function startLockCountdown(expiresAt) {
  const el = document.getElementById("qrisLockCountdown");
  if (!el || !expiresAt) return;

  if (state.qrisCountdownTimer) {
    clearInterval(state.qrisCountdownTimer);
  }

  const expiresAtMs = expiresAt instanceof Date ? expiresAt.getTime() : new Date(expiresAt).getTime();

  const render = () => {
    const remainingMs = expiresAtMs - Date.now();
    if (remainingMs <= 0) {
      el.textContent = "Nominal QRIS ini mungkin akan digunakan ulang untuk pesanan lain. Jika Anda sudah membayar, pembayaran tetap akan terverifikasi.";
      clearInterval(state.qrisCountdownTimer);
      state.qrisCountdownTimer = null;
      return;
    }
    const totalSeconds = Math.ceil(remainingMs / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    el.textContent = `Nominal berlaku selama ${minutes}:${String(seconds).padStart(2, "0")} lagi`;
  };

  render();
  state.qrisCountdownTimer = setInterval(render, 1000);
}

/**
 * Fire-and-forget call to the on-demand backend check. This nudges a
 * faster DSP transaction check (throttled server-side across all
 * concurrently polling customers) instead of waiting for the once-a-minute
 * scheduled function. Any failure here is non-fatal — the client still
 * relies on reading the order's paymentStatus directly from Firestore.
 */
function pingCheckPaymentNow(orderId) {
  const endpoint = (window.QRIS_CONFIG || {}).checkPaymentEndpoint;
  if (!endpoint) return;
  fetch(`${endpoint}?orderId=${encodeURIComponent(orderId)}`).catch(err => {
    console.warn("check-payment-now request failed (non-fatal):", err);
  });
}

/**
 * Start polling for payment status
 * Checks Firestore periodically to see if payment has been confirmed
 */
function startPaymentPolling(orderId) {
  const qrisConfig = window.QRIS_CONFIG || {};
  const pollingInterval = qrisConfig.pollingInterval || 3000; // Poll every 3 seconds
  const maxPollingTime = qrisConfig.maxPollingTime || 600000; // Stop polling after 10 minutes
  let pollingStartTime = Date.now();
  
  document.getElementById("qrisPollingStatus").style.display = "flex";
  
  // Clear any existing polling timer
  if (state.qrisPollingTimer) {
    clearInterval(state.qrisPollingTimer);
  }
  
  state.qrisPollingTimer = setInterval(async () => {
    const elapsedTime = Date.now() - pollingStartTime;
    
    // Stop polling after max time
    if (elapsedTime > maxPollingTime) {
      clearInterval(state.qrisPollingTimer);
      state.qrisPollingTimer = null;
      showQrisError("Waktu tunggu pembayaran telah habis. Silakan hubungi penjual.");
      return;
    }
    
    // Nudge the backend to check DSP for new transactions (throttled
    // server-side; safe to call every tick).
    pingCheckPaymentNow(orderId);

    try {
      // Check order status from Firestore
      const { doc, getDoc } = state._firestore;
      const orderRef = doc(state.db, "orders", orderId);
      const orderSnap = await getDoc(orderRef);
      
      if (orderSnap.exists()) {
        const orderData = orderSnap.data();
        
        // Check if payment has been confirmed
        if (orderData.paymentStatus === "PAID") {
          clearInterval(state.qrisPollingTimer);
          state.qrisPollingTimer = null;
          handlePaymentConfirmed(orderId);
        } else if (orderData.paymentStatus === "FAILED" || orderData.paymentStatus === "CANCELLED") {
          clearInterval(state.qrisPollingTimer);
          state.qrisPollingTimer = null;
          showQrisError("Pembayaran ditolak atau dibatalkan. Silakan coba lagi.");
        }
      }
    } catch (error) {
      console.error("Error polling payment status:", error);
      // Continue polling despite errors
    }
  }, pollingInterval);
}

/**
 * Handle confirmed payment
 */
function handlePaymentConfirmed(orderId) {
  const qrWrapper = document.getElementById("qrisQrWrapper");
  const pollingStatus = document.getElementById("qrisPollingStatus");
  const qrisStatus = document.getElementById("qrisStatus");
  
  if (state.qrisCountdownTimer) {
    clearInterval(state.qrisCountdownTimer);
    state.qrisCountdownTimer = null;
  }

  // Hide polling indicator
  pollingStatus.style.display = "none";
  qrWrapper.style.display = "none";
  
  // Show success message
  qrisStatus.innerHTML = `
    <div style="background: #E8F5E9; border-left: 4px solid var(--success); padding: 12px; border-radius: var(--radius-sm); margin: 0;">
      <p style="color: var(--success); font-weight: 500; margin: 0; font-size: 14px;">✓ Pembayaran Berhasil!</p>
      <p style="color: #558B2F; font-size: 13px; margin: 4px 0 0 0;">Pesanan Anda sedang diproses oleh penjual.</p>
    </div>
  `;
  
  // Update success message
  document.getElementById("successMessage").textContent = "Pembayaran berhasil diterima! Pesanan Anda sedang diproses.";
}

/**
 * Show QRIS error message
 */
function showQrisError(message) {
  const qrisStatus = document.getElementById("qrisStatus");
  const pollingStatus = document.getElementById("qrisPollingStatus");
  const qrWrapper = document.getElementById("qrisQrWrapper");
  
  if (state.qrisCountdownTimer) {
    clearInterval(state.qrisCountdownTimer);
    state.qrisCountdownTimer = null;
  }

  pollingStatus.style.display = "none";
  qrWrapper.style.display = "none";
  
  qrisStatus.innerHTML = `
    <div style="background: #FFEBEE; border-left: 4px solid var(--error); padding: 12px; border-radius: var(--radius-sm); margin: 0;">
      <p style="color: var(--error); font-weight: 500; margin: 0; font-size: 14px;">⚠ Pembayaran Gagal</p>
      <p style="color: #C62828; font-size: 13px; margin: 4px 0 0 0;">${escHtml(message)}</p>
    </div>
  `;
}
