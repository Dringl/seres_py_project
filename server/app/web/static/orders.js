const BASE = window.BASE || "";
function esc(s){return String(s==null?"":s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));}
const ST={CREATED:"已创建",ASSIGNED:"已派单",RESERVED:"已预约",BOARDING:"登机中",IN_FLIGHT:"飞行中",RETURNING:"返航中",DONE:"已完成",CANCELED:"已取消",FAILED:"失败"};
function zh(s){return ST[s]||s;}
async function call(method, path, body) {
  const opt = { method, credentials: "same-origin", headers: { "Content-Type": "application/json" } };
  if (body) opt.body = JSON.stringify(body);
  const r = await fetch(BASE + "/admin/api" + path, opt);
  if (!r.ok) { alert("失败: " + r.status); throw new Error(r.status); }
  return r.json();
}
async function loadOrders() {
  const rows = await fetch(BASE + "/admin/api/orders", { credentials: "same-origin" }).then((r) => r.json());
  document.querySelector("#orders-table tbody").innerHTML = rows.map((o) =>
    "<tr><td>" + esc(o.id) + "</td><td>" + esc(zh(o.status)) + "</td><td>" + esc(o.vehicleId) + "</td><td>" + esc(o.pickupVertiport.name) +
    "</td><td>" + esc(o.destination.name) + '</td><td><button class="btn" onclick="cancelOrder(\'' + o.id + '\')">强制取消</button></td></tr>'
  ).join("");
}
async function cancelOrder(id) { if (confirm("强制取消 " + id + "?")) { await call("POST", "/orders/" + id + "/cancel"); loadOrders(); } }
