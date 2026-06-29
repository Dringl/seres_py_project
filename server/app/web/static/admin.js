const BASE = window.BASE || "";
async function crud(method, path, body) {
  const opt = { method, credentials: "same-origin", headers: { "Content-Type": "application/json" } };
  if (body) opt.body = JSON.stringify(body);
  const r = await fetch(BASE + "/admin/api" + path, opt);
  if (!r.ok && r.status !== 204) { alert("操作失败: " + r.status); throw new Error(r.status); }
  return r.status === 204 ? null : r.json();
}
const val = (id) => document.getElementById(id).value.trim();

async function loadVehicles() {
  const rows = await crud("GET", "/vehicles");
  document.querySelector("#vehicles-table tbody").innerHTML = rows.map((v) =>
    "<tr><td>" + v.id + "</td><td>" + v.name + "</td><td>" + v.status + "</td><td>" + v.batteryPercent +
    '%</td><td>' + (v.currentVertiportId || "-") + '</td><td><button class="btn" onclick="delVehicle(\'' + v.id + '\')">删除</button> ' +
    '<button class="btn" onclick="toggleMaint(\'' + v.id + '\',\'' + v.status + '\')">维护切换</button></td></tr>'
  ).join("");
}
async function createVehicle() {
  await crud("POST", "/vehicles", { name: val("nv-name"), latitude: +val("nv-lat"), longitude: +val("nv-lng"), batteryPercent: +val("nv-batt") || 100, currentVertiportId: val("nv-vp") || null });
  loadVehicles();
}
async function delVehicle(id) { if (confirm("删除 " + id + "?")) { await crud("DELETE", "/vehicles/" + id); loadVehicles(); } }
async function toggleMaint(id, status) { await crud("PUT", "/vehicles/" + id, { status: status === "MAINTENANCE" ? "IDLE" : "MAINTENANCE" }); loadVehicles(); }

async function loadVertiports() {
  const rows = await crud("GET", "/vertiports");
  document.querySelector("#vertiports-table tbody").innerHTML = rows.map((vp) =>
    "<tr><td>" + vp.id + "</td><td>" + vp.name + '</td><td><span class="tag ' + (vp.occupied ? "occupied" : "empty") + '">' +
    (vp.occupied ? "有(" + vp.vehicleCount + ")" : "无") + '</span></td><td><button class="btn" onclick="delVertiport(\'' + vp.id + '\')">删除</button></td></tr>'
  ).join("");
}
async function createVertiport() {
  await crud("POST", "/vertiports", { id: val("np-id"), name: val("np-name"), latitude: +val("np-lat"), longitude: +val("np-lng") });
  loadVertiports();
}
async function delVertiport(id) { if (confirm("删除 " + id + "?")) { await crud("DELETE", "/vertiports/" + id); loadVertiports(); } }
