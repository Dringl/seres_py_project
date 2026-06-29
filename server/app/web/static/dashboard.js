const BASE = window.BASE || "";
function esc(s){return String(s==null?"":s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));}
const api = (p) => fetch(BASE + "/admin/api" + p, { credentials: "same-origin" }).then((r) => r.json());

let map = null, markers = [];
function initMap() {
  if (window.AMap && document.getElementById("map")) {
    map = new AMap.Map("map", { zoom: 11, center: [106.55, 29.56] });
  }
}
function drawMap(vehicles, vertiports) {
  if (!map || !window.AMap) return;
  markers.forEach((m) => map.remove(m));
  markers = [];
  vertiports.forEach((vp) => {
    const m = new AMap.Marker({ position: [vp.longitude, vp.latitude], title: vp.name, content: '<div style="background:#1d9e75;color:#fff;padding:2px 6px;border-radius:6px;font-size:11px">' + esc(vp.name) + (vp.occupied ? " ●" : "") + "</div>" });
    map.add(m); markers.push(m);
  });
  vehicles.forEach((v) => {
    const m = new AMap.Marker({ position: [v.longitude, v.latitude], title: v.id, content: '<div style="background:#534ab7;color:#fff;padding:2px 6px;border-radius:6px;font-size:11px">' + esc(v.id) + " " + esc(v.status) + "</div>" });
    map.add(m); markers.push(m);
  });
}

function renderCards(o) {
  document.getElementById("overview-cards").innerHTML = [
    ["机队总数", o.vehicles.total], ["空闲", o.vehicles.idle], ["飞行中", o.vehicles.in_flight],
    ["进行中订单", o.orders.active], ["被占用停机坪", o.occupiedVertiports],
  ].map(([l, n]) => '<div class="card"><div class="n">' + n + '</div><div class="l">' + l + "</div></div>").join("");
}
function renderFleet(vehicles) {
  document.querySelector("#fleet-table tbody").innerHTML = vehicles.map((v) =>
    "<tr><td>" + esc(v.id) + "</td><td>" + esc(v.name) + "</td><td>" + esc(v.status) + "</td><td>" + v.batteryPercent + "%</td><td>" + esc(v.currentVertiportId || "-") + "</td></tr>"
  ).join("");
}
function renderOccupancy(vertiports) {
  document.getElementById("occupancy-panel").innerHTML =
    '<table><thead><tr><th>停机坪</th><th>占用</th><th>飞行器</th></tr></thead><tbody>' +
    vertiports.map((vp) =>
      "<tr><td>" + esc(vp.name) + '</td><td><span class="tag ' + (vp.occupied ? "occupied" : "empty") + '">' +
      (vp.occupied ? "有 (" + vp.vehicleCount + ")" : "无") + "</span></td><td>" + (vp.vehicleIds.map(esc).join(", ") || "-") + "</td></tr>"
    ).join("") + "</tbody></table>";
}

async function refresh() {
  try {
    const [o, vehicles, vertiports] = await Promise.all([api("/overview"), api("/vehicles"), api("/vertiports")]);
    renderCards(o); renderFleet(vehicles); renderOccupancy(vertiports); drawMap(vehicles, vertiports);
  } catch (e) { /* 网络抖动忽略，下一拍重试 */ }
}

initMap();
refresh();
setInterval(refresh, 2000);
