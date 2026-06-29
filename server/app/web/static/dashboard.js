const BASE = window.BASE || "";
function esc(s){return String(s==null?"":s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));}
const api = (p) => fetch(BASE + "/admin/api" + p, { credentials: "same-origin" }).then((r) => r.json());

let map = null, markers = [];
// 与 Android app 一致的地图图标（由 res/drawable 的 vector drawable 转为内联 SVG）
const ICON_VERTIPORT =
  '<svg width="32" height="32" viewBox="0 0 512 512" xmlns="http://www.w3.org/2000/svg"><path fill="#F59E0B" d="M256,0C114.6,0 0,114.6 0,256s114.6,256 256,256s256,-114.6 256,-256S397.4,0 256,0zM368,360c0,13.25 -10.75,24 -24,24S320,373.3 320,360v-80H192v80C192,373.3 181.3,384 168,384S144,373.3 144,360v-208C144,138.8 154.8,128 168,128S192,138.8 192,152v80h128v-80C320,138.8 330.8,128 344,128s24,10.75 24,24V360z"/></svg>';
const ICON_PLANE =
  '<svg width="28" height="28" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path fill="#0F172A" d="M21,16v-2l-8,-5V3.5c0,-0.83 -0.67,-1.5 -1.5,-1.5S10,2.67 10,3.5V9l-8,5v2l8,-2.5V19l-2,1.5V22l3.5,-1l3.5,1v-1.5L13,19v-5.5l8,2.5z"/></svg>';
const ICON_PLANE_FLYING =
  '<svg width="28" height="28" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path fill="#1D9BFF" d="M21,16v-2l-8,-5V3.5c0,-0.83 -0.67,-1.5 -1.5,-1.5S10,2.67 10,3.5V9l-8,5v2l8,-2.5V19l-2,1.5V22l3.5,-1l3.5,1v-1.5L13,19v-5.5l8,2.5z"/></svg>';

function initMap() {
  if (window.AMap && document.getElementById("map")) {
    map = new AMap.Map("map", { zoom: 11, center: [106.55, 29.56] });
  }
}
function iconMarker(lng, lat, title, html, size) {
  return new AMap.Marker({ position: [lng, lat], title: title, content: html, offset: new AMap.Pixel(-size / 2, -size / 2) });
}
function drawMap(vehicles, vertiports) {
  if (!map || !window.AMap) return;
  markers.forEach((m) => map.remove(m));
  markers = [];
  vertiports.forEach((vp) => {
    const m = iconMarker(vp.longitude, vp.latitude, vp.name, ICON_VERTIPORT, 32);
    map.add(m); markers.push(m);
  });
  vehicles.forEach((v) => {
    const flying = v.status === "IN_FLIGHT" || v.status === "RETURNING";
    const m = iconMarker(v.longitude, v.latitude, v.id + " " + v.status,
      flying ? ICON_PLANE_FLYING : ICON_PLANE, 28);
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
