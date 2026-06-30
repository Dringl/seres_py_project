const BASE = window.BASE || "";
function esc(s){return String(s==null?"":s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));}
const ST={IDLE:"空闲",RESERVED:"已预约",BOARDING:"登机中",IN_FLIGHT:"飞行中",CHARGING:"充电中",MAINTENANCE:"维护中",OFFLINE:"离线",CREATED:"已创建",ASSIGNED:"已派单",RETURNING:"返航中",DONE:"已完成",CANCELED:"已取消",FAILED:"失败"};
function zh(s){return ST[s]||s;}
const api = (p) => fetch(BASE + "/admin/api" + p, { credentials: "same-origin" }).then((r) => r.json());

const POLL_MS = 2000;
let map = null, vertMarkers = [], routes = [];
const vehMarkers = {}; // id -> {marker, curLng,curLat, startLng,startLat, targetLng,targetLat, t0, dur, angle, flying}

// 与 Android app 一致的地图图标（由 res/drawable 的 vector drawable 转为内联 SVG）
const ICON_VERTIPORT =
  '<svg width="32" height="32" viewBox="0 0 512 512" xmlns="http://www.w3.org/2000/svg"><path fill="#F59E0B" d="M256,0C114.6,0 0,114.6 0,256s114.6,256 256,256s256,-114.6 256,-256S397.4,0 256,0zM368,360c0,13.25 -10.75,24 -24,24S320,373.3 320,360v-80H192v80C192,373.3 181.3,384 168,384S144,373.3 144,360v-208C144,138.8 154.8,128 168,128S192,138.8 192,152v80h128v-80C320,138.8 330.8,128 344,128s24,10.75 24,24V360z"/></svg>';
const ICON_PLANE =
  '<svg width="28" height="28" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path fill="#0F172A" d="M21,16v-2l-8,-5V3.5c0,-0.83 -0.67,-1.5 -1.5,-1.5S10,2.67 10,3.5V9l-8,5v2l8,-2.5V19l-2,1.5V22l3.5,-1l3.5,1v-1.5L13,19v-5.5l8,2.5z"/></svg>';
const ICON_PLANE_FLYING =
  '<svg width="28" height="28" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path fill="#1D9BFF" d="M21,16v-2l-8,-5V3.5c0,-0.83 -0.67,-1.5 -1.5,-1.5S10,2.67 10,3.5V9l-8,5v2l8,-2.5V19l-2,1.5V22l3.5,-1l3.5,1v-1.5L13,19v-5.5l8,2.5z"/></svg>';

// 罗盘航向角（0=正北，顺时针），图标默认朝北，用 CSS transform 旋转
function bearing(lng1, lat1, lng2, lat2) {
  const r = Math.PI / 180;
  const dLng = (lng2 - lng1) * r;
  const y = Math.sin(dLng) * Math.cos(lat2 * r);
  const x = Math.cos(lat1 * r) * Math.sin(lat2 * r) - Math.sin(lat1 * r) * Math.cos(lat2 * r) * Math.cos(dLng);
  return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
}
function iconHtml(svg, size, angle) {
  return '<div style="width:' + size + 'px;height:' + size + 'px;transform:rotate(' + (angle || 0) + 'deg);transform-origin:center center;">' + svg + '</div>';
}

function initMap() {
  if (window.AMap && document.getElementById("map")) {
    map = new AMap.Map("map", { zoom: 11, center: [106.55, 29.56] });
    requestAnimationFrame(animate); // 60fps 插值循环
  }
}

// 每帧把各飞行器从上一采样点平滑插值滑到最新采样点
function animate() {
  const now = Date.now();
  for (const id in vehMarkers) {
    const r = vehMarkers[id];
    let p = r.dur > 0 ? (now - r.t0) / r.dur : 1;
    if (p > 1) p = 1;
    r.curLng = r.startLng + (r.targetLng - r.startLng) * p;
    r.curLat = r.startLat + (r.targetLat - r.startLat) * p;
    r.marker.setPosition([r.curLng, r.curLat]);
  }
  requestAnimationFrame(animate);
}

function drawVertiports(vertiports) {
  if (vertMarkers.length) return; // 停机坪静止，建一次即可
  vertiports.forEach((vp) => {
    const m = new AMap.Marker({
      position: [vp.longitude, vp.latitude], title: vp.name,
      content: iconHtml(ICON_VERTIPORT, 32, 0), offset: new AMap.Pixel(-16, -16), zIndex: 60
    });
    map.add(m); vertMarkers.push(m);
  });
}

function drawRoutes(orders, vehById) {
  routes.forEach((p) => map.remove(p));
  routes = [];
  (orders || []).forEach((ord) => {
    const veh = vehById[ord.vehicleId];
    let from = null, to = null;
    if (ord.status === "RESERVED" && veh) {
      from = [veh.longitude, veh.latitude];
      to = [ord.pickupVertiport.location.longitude, ord.pickupVertiport.location.latitude];
    } else if (ord.status === "IN_FLIGHT" && veh) {
      from = [veh.longitude, veh.latitude];
      to = [ord.destination.location.longitude, ord.destination.location.latitude];
    } else if (ord.status === "BOARDING") {
      from = [ord.pickupVertiport.location.longitude, ord.pickupVertiport.location.latitude];
      to = [ord.destination.location.longitude, ord.destination.location.latitude];
    }
    if (from && to) {
      const pl = new AMap.Polyline({
        path: [from, to], strokeColor: "#1D9BFF", strokeWeight: 4,
        strokeStyle: "dashed", strokeOpacity: 0.85, showDir: true, zIndex: 50
      });
      map.add(pl); routes.push(pl);
    }
  });
}

function updateVehicles(vehicles) {
  const seen = {};
  vehicles.forEach((v) => {
    seen[v.id] = 1;
    const flying = v.status === "IN_FLIGHT" || v.status === "RETURNING";
    const svg = flying ? ICON_PLANE_FLYING : ICON_PLANE;
    let r = vehMarkers[v.id];
    if (!r) {
      const m = new AMap.Marker({
        position: [v.longitude, v.latitude], title: v.id + " " + zh(v.status),
        content: iconHtml(svg, 28, 0), offset: new AMap.Pixel(-14, -14), zIndex: 80
      });
      map.add(m);
      vehMarkers[v.id] = {
        marker: m, curLng: v.longitude, curLat: v.latitude,
        startLng: v.longitude, startLat: v.latitude, targetLng: v.longitude, targetLat: v.latitude,
        t0: Date.now(), dur: 0, angle: 0, flying: flying
      };
      return;
    }
    // 朝向：上一采样点 -> 新采样点
    let angle = r.angle;
    if (Math.abs(r.targetLng - v.longitude) > 1e-7 || Math.abs(r.targetLat - v.latitude) > 1e-7) {
      angle = bearing(r.targetLng, r.targetLat, v.longitude, v.latitude);
    }
    if (angle !== r.angle || flying !== r.flying) {
      r.marker.setContent(iconHtml(svg, 28, angle));
      r.angle = angle; r.flying = flying;
    }
    // 从当前已插值到的位置，平滑滑到新采样点（耗时一个轮询周期）
    r.startLng = r.curLng; r.startLat = r.curLat;
    r.targetLng = v.longitude; r.targetLat = v.latitude;
    r.t0 = Date.now(); r.dur = POLL_MS;
  });
  for (const id in vehMarkers) {
    if (!seen[id]) { map.remove(vehMarkers[id].marker); delete vehMarkers[id]; }
  }
}

function drawMap(vehicles, vertiports, orders) {
  if (!map || !window.AMap) return;
  const vehById = {};
  vehicles.forEach((v) => { vehById[v.id] = v; });
  drawVertiports(vertiports);
  drawRoutes(orders, vehById);
  updateVehicles(vehicles);
}

function renderCards(o) {
  document.getElementById("overview-cards").innerHTML = [
    ["机队总数", o.vehicles.total], ["空闲", o.vehicles.idle], ["飞行中", o.vehicles.in_flight],
    ["进行中订单", o.orders.active], ["被占用停机坪", o.occupiedVertiports],
  ].map(([l, n]) => '<div class="card"><div class="n">' + n + '</div><div class="l">' + l + "</div></div>").join("");
}
function renderFleet(vehicles) {
  document.querySelector("#fleet-table tbody").innerHTML = vehicles.map((v) =>
    "<tr><td>" + esc(v.id) + "</td><td>" + esc(v.name) + "</td><td>" + esc(zh(v.status)) + "</td><td>" + v.batteryPercent + "%</td><td>" + esc(v.currentVertiportId || "-") + "</td></tr>"
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
    const [o, vehicles, vertiports, orders] = await Promise.all([api("/overview"), api("/vehicles"), api("/vertiports"), api("/orders")]);
    renderCards(o); renderFleet(vehicles); renderOccupancy(vertiports); drawMap(vehicles, vertiports, orders);
  } catch (e) { /* 网络抖动忽略，下一拍重试 */ }
}

initMap();
refresh();
setInterval(refresh, POLL_MS);
