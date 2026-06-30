const BASE = window.BASE || "";
function esc(s){return String(s==null?"":s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));}
function fmt(ms){ if(!ms){return "-";} const d=new Date(ms); return d.toLocaleString("zh-CN"); }
async function loadUsers() {
  const rows = await fetch(BASE + "/admin/api/users", { credentials: "same-origin" }).then((r) => r.json());
  document.querySelector("#users-table tbody").innerHTML = rows.map((u) =>
    "<tr><td>" + esc(u.id) + "</td><td>" + esc(u.username) + "</td><td>" + esc(fmt(u.createdAt)) + "</td><td>" + esc(u.orderCount) + "</td></tr>"
  ).join("");
}
