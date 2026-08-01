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
  categories:  [],
  menuItems:   [],
  cart:        [],    // [{ menuItem, quantity, selectedToppings, notes, subtotal }]
  orders:      [],    // User's order history for this session
  activeCategory: "all",
  isLoading:   true,
  db:          null,
  ordersListener: null
};

// ---------------------------------------------------------------------------
// Initialise
// ---------------------------------------------------------------------------
document.addEventListener("DOMContentLoaded", async () => {
  parseTableFromUrl();
  await initFirebase();
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
  document.getElementById("tableLabel").textContent = `Meja ${state.tableNumber}`;
  document.title = `Menu – Meja ${state.tableNumber}`;
}

async function initFirebase() {
  try {
    const { initializeApp } = await import(
      "https://www.gstatic.com/firebasejs/10.14.0/firebase-app.js"
    );
    const { getFirestore, collection, getDocs, query, where, addDoc, serverTimestamp, onSnapshot, orderBy } = await import(
      "https://www.gstatic.com/firebasejs/10.14.0/firebase-firestore.js"
    );
    const app = initializeApp(FIREBASE_CONFIG);
    state.db = getFirestore(app);
    // Expose Firestore helpers on state for later use
    state._firestore = { collection, getDocs, query, where, addDoc, serverTimestamp, onSnapshot, orderBy };
  } catch (err) {
    console.error("Firebase init failed:", err);
    showError("Tidak dapat terhubung ke server.");
  }
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
      .filter(c => c.isActive)
      .sort((a, b) => (a.displayOrder || 0) - (b.displayOrder || 0));

    // Load menu items
    const menuSnap = await getDocs(collection(state.db, "menuItems"));
    state.menuItems = menuSnap.docs.map(d => ({ id: d.id, ...d.data() }));

    renderCategoryTabs();
    renderMenuGrid("all");
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
          
          // Keep completed orders only if within last 24 hours
          if (order.status === "COMPLETED") {
            const orderDate = order.updatedAt?.toDate ? order.updatedAt.toDate() : 
                            order.createdAt?.toDate ? order.createdAt.toDate() : new Date(0);
            return orderDate >= oneDayAgo;
          }
          
          // Keep all other statuses (PENDING, PREPARING, READY)
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

  updateCartUI();
  showCartToast(item.name);
}

function removeFromCart(key) {
  state.cart = state.cart.filter(c => c.key !== key);
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
      PENDING: { text: "Menunggu", color: "#FF9800" },
      PREPARING: { text: "Diproses", color: "#2196F3" },
      READY: { text: "Siap", color: "#4CAF50" },
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
      <div class="cart-item" style="border-left: 3px solid ${status.color}">
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

  document.getElementById("cartDrawer").classList.remove("open");
  document.getElementById("overlay").style.display = "none";
  document.body.style.overflow = "";
}

// ---------------------------------------------------------------------------
// Checkout
// ---------------------------------------------------------------------------
function goToCheckout() {
  if (state.cart.length === 0) return;
  closeCart();

  const items = state.cart.map(c => `
    <div class="checkout-item">
      <div class="checkout-item-name">${c.quantity}× ${escHtml(c.menuItem.name)}</div>
      <div class="checkout-item-price">${formatRupiah(c.subtotal)}</div>
    </div>`).join("");

  document.getElementById("checkoutItems").innerHTML = items;
  const total = state.cart.reduce((s, c) => s + c.subtotal, 0);
  document.getElementById("checkoutSubtotal").textContent = formatRupiah(total);
  document.getElementById("checkoutTotal").textContent = formatRupiah(total);

  document.getElementById("checkoutPage").style.display = "block";
  document.body.style.overflow = "hidden";
}

function closeCheckout() {
  document.getElementById("checkoutPage").style.display = "none";
  document.body.style.overflow = "";
}

async function placeOrder() {
  const btn = document.getElementById("placeOrderBtn");
  btn.disabled = true;
  btn.textContent = "Memproses...";

  try {
    const customerName = document.getElementById("customerName").value.trim();
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

    const order = {
      tableNumber:   state.tableNumber,
      sessionId:     state.sessionId,    // Unique session ID to identify this customer
      customerName:  customerName,
      items:         orderItems,
      status:        "PENDING",
      paymentMethod: paymentMethod,
      notes:         notes,
      createdAt:     state._firestore.serverTimestamp(),
      updatedAt:     state._firestore.serverTimestamp(),
      estimatedReadyMinutes: 15
    };

    // Import transaction functions
    const { runTransaction, doc } = await import(
      "https://www.gstatic.com/firebasejs/10.14.0/firebase-firestore.js"
    );

    // Use transaction to check and update stock atomically
    const orderId = await runTransaction(state.db, async (transaction) => {
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

      // Create the order
      const orderRef = doc(state.db, "orders", generateId());
      transaction.set(orderRef, order);
      return orderRef.id;
    });

    showSuccess(orderId, state.tableNumber, paymentMethod);
  } catch (err) {
    console.error("placeOrder error:", err);
    alert(err.message || "Gagal mengirim pesanan. Coba lagi.");
    btn.disabled = false;
    btn.textContent = "Pesan Sekarang";
  }
}

// ---------------------------------------------------------------------------
// Success
// ---------------------------------------------------------------------------
function showSuccess(orderId, tableNumber, paymentMethod) {
  const paymentLabels = {
    QRIS: "QRIS",
    BANK_TRANSFER: "Transfer Bank",
    CASHIER: "Bayar di Kasir"
  };

  document.getElementById("successOrderInfo").innerHTML = `
    <div class="order-info-row"><span>No. Pesanan</span><strong>#${escHtml(orderId.slice(-6).toUpperCase())}</strong></div>
    <div class="order-info-row"><span>Meja</span><strong>${escHtml(String(tableNumber))}</strong></div>
    <div class="order-info-row"><span>Pembayaran</span><strong>${escHtml(paymentLabels[paymentMethod] || paymentMethod)}</strong></div>
  `;

  document.getElementById("checkoutPage").style.display = "none";
  document.getElementById("successPage").style.display = "block";
  state.cart = [];
  updateCartUI();
  // Order history will automatically update via real-time listener
}

function resetApp() {
  document.getElementById("successPage").style.display = "none";
  document.body.style.overflow = "";
  document.getElementById("customerName").value = "";
  document.getElementById("orderNotes").value = "";
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
