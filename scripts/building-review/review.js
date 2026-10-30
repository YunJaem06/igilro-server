"use strict";
(() => {
  const data = JSON.parse(document.getElementById("review-data").textContent);
  const $ = id => document.getElementById(id);
  const number = value => new Intl.NumberFormat("ko-KR", {maximumFractionDigits: 1}).format(value);
  const element = (tag, text, className) => {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = String(text);
    if (className) node.className = className;
    return node;
  };
  const positive = value => Number.isFinite(Number(value)) && Number(value) > 0 ? Number(value) : null;
  const shown = value => value === null || value === undefined || String(value).trim() === "" ? "미제공" : String(value);
  const heightText = value => positive(value) === null ? "미확인" : `${number(Number(value))}m`;
  const REASONS = {
    NO_HUB_PARCEL: "일치하는 HUB 필지 자료가 없습니다. 지번 변경과 원본 기준 시점을 추가로 확인해야 합니다.",
    MATCHED_WITHOUT_DIRECT_HEIGHT: "대장 식별자는 연결됐지만 원본에 직접 높이가 없습니다. 추가 높이 자료가 필요합니다.",
    NO_DIRECT_HEIGHT_ON_PARCEL: "같은 필지의 대장에서도 직접 높이를 찾지 못했습니다.",
    UNRESOLVED_BUILDING_IDENTITY: "같은 필지에 높이 후보가 있지만 어느 건물인지 확정하지 못했습니다.",
    DUPLICATE_GIS_PK: "여러 GIS 행이 같은 대장 PK를 사용합니다. 도형과 원본 식별자를 함께 대조해야 합니다.",
    AMBIGUOUS_HUB_PK: "같은 PK에 여러 대장 후보가 있어 연결을 보류했습니다.",
    PK_AREA_CONFLICT: "대장 PK 후보와 GIS의 건축면적이 충돌해 HUB 연결을 보류했습니다.",
    PK_FLOOR_CONFLICT: "대장 PK 후보와 GIS의 지상층수가 충돌해 HUB 연결을 취소했습니다. 현재 값은 GIS 원본입니다.",
    PK_APPROVAL_DATE_CONFLICT: "대장 PK 후보와 GIS의 사용승인일이 달라 연결을 보류했습니다.",
    PK_PNU_CONFLICT: "변환 PK 후보와 GIS의 필지가 다릅니다. 지번·갱신 시점을 확인해야 합니다.",
    PK_CITY_CONFLICT: "변환 PK 후보와 GIS의 시군구가 다릅니다.",
    INVALID_OR_UNSUPPORTED_PK: "기존 PK 형식이 유효하지 않거나 현재 시험 지역의 전환 범위 밖입니다.",
    PK_NOT_FOUND: "변환한 PK가 이번에 수집한 HUB 표제부에 없습니다.",
    NO_LEGACY_PK: "GIS 원본에 기존 건축물대장 식별자가 없습니다.",
    VERIFIED_OFFICIAL_PK: "공식 전환 PK·필지와 양쪽에 존재하는 대장 속성이 일치합니다. 현장 높이 검증 완료를 뜻하지는 않습니다."
  };
  const PK_LABELS = {
    VERIFIED_OFFICIAL_PK: "PK·필지·속성 일치", NO_LEGACY_PK: "기존 대장 ID 없음", PK_NOT_FOUND: "변환 PK 미조회",
    DUPLICATE_GIS_PK: "GIS PK 중복", AMBIGUOUS_HUB_PK: "HUB PK 중복", PK_AREA_CONFLICT: "건축면적 충돌",
    PK_FLOOR_CONFLICT: "지상층수 충돌", PK_APPROVAL_DATE_CONFLICT: "사용승인일 충돌", PK_PNU_CONFLICT: "필지 충돌",
    PK_CITY_CONFLICT: "시군구 충돌", PK_DONG_CONFLICT: "동명 충돌", INVALID_OR_UNSUPPORTED_PK: "PK 형식·범위 미지원"
  };
  const REVIEW_LABELS = {unreviewed: "미검토", in_progress: "검토 중", needs_data: "추가 자료 필요", reviewed: "검토 완료"};
  const buildings = data.buildings;
  const byId = new Map(buildings.map(building => [building.id, building]));
  const canvas = $("map"), ctx = canvas.getContext("2d");
  const storageKey = `igilro-building-review-v1:${data.dataVersion}`;
  const notes = new Map();
  let storageOkay = true, storageWarned = false, toastTimer, noteTimer, searchTimer;
  let selected = null, overlaps = [], page = 0, filtered = [], visibleIds = new Set(), drawPending = false;
  let currentPilot = null;
  const PAGE_SIZE = 40;
  const view = {x: 0, y: 0, scale: 1, width: 1, height: 1, dpr: 1};
  const reference = data.route.coordinates[Math.floor(data.route.coordinates.length / 2)];
  const R = 6378137, RAD = Math.PI / 180, cosine = Math.cos(reference[1] * RAD);
  const originY = R * Math.log(Math.tan(Math.PI / 4 + reference[1] * RAD / 2));
  function project(point) {
    return [(point[0] - reference[0]) * RAD * R * cosine,
      (R * Math.log(Math.tan(Math.PI / 4 + point[1] * RAD / 2)) - originY) * cosine];
  }
  const baseRoute = data.route.coordinates.map(project);
  let route = baseRoute;
  let routeSegments = route.slice(1).map((point, index) => [route[index], point]);
  const bounds = points => [Math.min(...points.map(p => p[0])), Math.min(...points.map(p => p[1])),
    Math.max(...points.map(p => p[0])), Math.max(...points.map(p => p[1]))];
  const routeBounds = bounds(route);
  const intersectsBox = (a, b) => a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1];
  function pointSegment(point, a, b) {
    const dx = b[0] - a[0], dy = b[1] - a[1], length = dx * dx + dy * dy;
    const t = length === 0 ? 0 : Math.max(0, Math.min(1, ((point[0] - a[0]) * dx + (point[1] - a[1]) * dy) / length));
    return (point[0] - a[0] - t * dx) ** 2 + (point[1] - a[1] - t * dy) ** 2;
  }
  function segmentDistance(a, b, c, d) {
    const cross = (p, q, r) => (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0]);
    if (Math.max(a[0], b[0]) >= Math.min(c[0], d[0]) && Math.max(c[0], d[0]) >= Math.min(a[0], b[0]) &&
        Math.max(a[1], b[1]) >= Math.min(c[1], d[1]) && Math.max(c[1], d[1]) >= Math.min(a[1], b[1]) &&
        cross(a, b, c) * cross(a, b, d) <= 0 && cross(c, d, a) * cross(c, d, b) <= 0) return 0;
    return Math.min(pointSegment(a, c, d), pointSegment(b, c, d), pointSegment(c, a, b), pointSegment(d, a, b));
  }
  function inRing(point, ring) {
    let inside = false;
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      const a = ring[i], b = ring[j];
      if ((a[1] > point[1]) !== (b[1] > point[1]) && point[0] < (b[0] - a[0]) * (point[1] - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside;
    }
    return inside;
  }
  const inBuilding = (point, building) => building.projected.some(polygon => inRing(point, polygon[0]) && !polygon.slice(1).some(ring => inRing(point, ring)));
  function routeDistance(building) {
    if (currentPilot) {
      const point = currentPilot.projectedCenter;
      if (inBuilding(point, building)) return 0;
      let minimum = Infinity;
      for (const polygon of building.projected) for (const ring of polygon) for (let i = 1; i < ring.length; i++) minimum = Math.min(minimum, pointSegment(point, ring[i - 1], ring[i]));
      return Math.sqrt(minimum);
    }
    if (inBuilding(route[0], building)) return 0;
    let minimum = Infinity;
    for (const polygon of building.projected) for (const ring of polygon) for (let i = 1; i < ring.length; i++) {
      for (const [a, b] of routeSegments) {
        minimum = Math.min(minimum, segmentDistance(ring[i - 1], ring[i], a, b));
        if (minimum === 0) return 0;
      }
    }
    return Math.sqrt(minimum);
  }
  const shortName = building => {
    const name = building.name || (building.audit.pkValidation === "VERIFIED_OFFICIAL_PK" ? String(building.hub?.bldNm || "").trim() : "");
    return name ? `${name}${building.dong && !name.includes(building.dong) ? " · " + building.dong : ""}` : `이름 미확인 건물 #${building.objectId}`;
  };
  const priority = building => building.conflict ? 0 : building.height === null ? 1 : 2;
  const distanceText = distance => distance < 1 ? (currentPilot ? "후보 중심과 겹침" : "경로와 겹침") : distance >= 1000 ? `약 ${number(distance / 1000)}km` : `약 ${number(Math.round(distance))}m`;
  const category = building => building.conflict ? "conflict" : building.height === null ? "unknown" : "known";
  const categoryText = building => building.conflict ? "매칭 충돌" : building.height === null ? "높이 누락" : "높이 확인";
  function toast(message) {
    $("toast").textContent = message; $("toast").hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => { $("toast").hidden = true; }, 4200);
  }
  const validateNotes = bundle => BuildingReviewNotes.validate(bundle, data.dataVersion, byId, REVIEW_LABELS);
  const noteBundle = () => ({schemaVersion: 1, dataVersion: data.dataVersion, exportedAt: new Date().toISOString(), records: [...notes.values()]});
  try {
    const saved = localStorage.getItem(storageKey);
    if (saved) for (const [id, row] of validateNotes(JSON.parse(saved))) notes.set(id, row);
  } catch { storageOkay = false; }
  function persistNotes() {
    try { localStorage.setItem(storageKey, JSON.stringify(noteBundle())); storageOkay = true; }
    catch { storageOkay = false; if (!storageWarned) { storageWarned = true; toast("브라우저에 저장할 수 없습니다. 메모 내보내기로 보관해 주세요."); } }
  }
  function saveNote(announce = false) {
    clearTimeout(noteTimer);
    noteTimer = null;
    if (!selected) return;
    notes.set(selected.id, {id: selected.id, status: $("note-status").value, note: $("note-text").value, updatedAt: new Date().toISOString()});
    persistNotes();
    $("note-save-state").textContent = storageOkay ? "브라우저에 저장됨" : "내보내기로 보관 필요";
    $("note-save-state").classList.remove("dirty-marker");
    applyFilters(false);
    if (announce) toast(storageOkay ? "검수 메모를 저장했습니다." : "현재 화면에만 보관 중입니다. JSON으로 내보내 주세요.");
  }
  function flushNote() { if (noteTimer) { clearTimeout(noteTimer); noteTimer = null; saveNote(); } }
  function scheduleNote() {
    $("note-save-state").textContent = "저장 대기 중…"; $("note-save-state").classList.add("dirty-marker");
    clearTimeout(noteTimer); noteTimer = setTimeout(() => { noteTimer = null; saveNote(); }, 450);
  }
  function fieldList(target, fields) {
    const list = $(target); list.replaceChildren();
    for (const [label, value] of fields) list.append(element("dt", label), element("dd", shown(value)));
  }
  function selectBuilding(building, focus = false, hitList = []) {
    flushNote(); selected = building; overlaps = hitList;
    $("inspector").hidden = false;
    $("detail-name").textContent = shortName(building);
    $("detail-badges").replaceChildren(element("span", categoryText(building), `badge ${category(building)}`),
      element("span", PK_LABELS[building.audit.pkValidation] || building.audit.pkValidation, "badge neutral"));
    $("detail-height").textContent = building.height === null ? "미확인" : number(building.height);
    $("detail-height-unit").textContent = building.height === null ? "" : "m";
    $("detail-height-provider").textContent = building.height === null ? "원본에도 높이가 없거나 건물 연결이 보류됐습니다." : building.heightProvider;
    const reason = building.conflict ? building.audit.pkValidation : building.audit.heightGapReason || building.audit.pkValidation;
    $("detail-reason").textContent = REASONS[reason] || "원본의 직접 높이를 보존했습니다. 추가 검증 근거를 메모에 기록해 주세요.";
    $("detail-reason").classList.toggle("good", !building.conflict && building.height !== null);
    fieldList("detail-identifiers", [["PNU", building.pnu], ["GIS 건물 ID", building.gis.buld_idntfc_no],
      ["변환 대장 PK", building.audit.convertedHubPk], ["연결 대장 PK", building.audit.hubSourceId],
      [currentPilot ? "후보 중심 거리" : "시험 경로 거리", distanceText(building.distance)], ["연결 방식", building.audit.method === "OFFICIAL_PK_PNU_ATTRIBUTES" ? "공식 PK + 필지·속성 대조" : building.audit.method || "연결 보류"]]);
    const table = element("table", undefined, "source-table");
    const head = element("tr"); for (const text of ["항목", "GIS 원본", "HUB 후보"]) head.append(element("th", text));
    const thead = element("thead"); thead.append(head); table.append(thead);
    const body = element("tbody");
    for (const values of [
      ["직접 높이", heightText(building.gis.hg), heightText(building.hub?.heit)],
      ["건축면적", positive(building.gis.ar) === null ? "미제공" : `${number(building.gis.ar)}㎡`, positive(building.hub?.archArea) === null ? "미제공" : `${number(building.hub.archArea)}㎡`],
      ["지상층수", positive(building.gis.ground_floor_co) === null ? "미제공" : `${building.gis.ground_floor_co}층`, positive(building.hub?.grndFlrCnt) === null ? "미제공" : `${building.hub.grndFlrCnt}층`],
      ["사용승인", shown(building.gis.use_confm_de), shown(building.hub?.useAprDay)],
      ["대장 주소", "PNU 기준", shown(building.hub?.newPlatPlc || building.hub?.platPlc)]
    ]) { const row = element("tr"); for (const value of values) row.append(element("td", value)); body.append(row); }
    table.append(body); $("source-comparison").replaceChildren(table);
    fieldList("detail-provenance", [["형상 출처", "국토교통부 GIS건물통합정보(브이월드)"], ["형상 원본 ID", building.sourceId],
      ["높이 종류", building.heightKind], ["높이 출처", building.heightProvider], ["높이 원본 ID", building.heightSourceId],
      ["GIS 갱신 표기", building.gis.last_updt_dt], ["응답 관측 시각", data.sourceTimestamp], ["자료 버전", data.dataVersion]]);
    const note = notes.get(building.id);
    $("note-status").value = note?.status || "unreviewed"; $("note-text").value = note?.note || "";
    $("note-save-state").textContent = note ? "저장된 메모" : "수정 시 자동 저장";
    $("note-save-state").classList.remove("dirty-marker");
    $("overlap-info").hidden = overlaps.length < 2;
    $("overlap-label").textContent = `이 위치에 겹친 도형 ${overlaps.length}개`;
    if (focus) fitBounds(building.box, 95, true);
    else {
      const inspectorWidth = $("inspector").offsetWidth;
      const sx = (building.box[0] + building.box[2]) / 2;
      view.x = sx + inspectorWidth / (2 * view.scale);
      view.y = (building.box[1] + building.box[3]) / 2;
    }
    renderList(); requestDraw();
  }
  function closeInspector() { flushNote(); $("inspector").hidden = true; selected = null; overlaps = []; renderList(); requestDraw(); }
  function renderList() {
    const pages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE)); page = Math.min(page, pages - 1);
    const fragment = document.createDocumentFragment();
    for (const building of filtered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)) {
      const button = element("button", undefined, `building-card${selected?.id === building.id ? " selected" : ""}`);
      button.dataset.buildingId = building.id;
      const title = element("div", undefined, "card-title");
      title.append(element("span", shortName(building)), element("span", building.height === null ? "—" : `${number(building.height)} m`));
      const meta = element("div", `PNU ${building.pnu}`, "card-meta");
      const bottom = element("div", undefined, "card-bottom");
      const state = element("span", categoryText(building), `badge ${category(building)}`);
      const review = notes.get(building.id)?.status;
      if (review && review !== "unreviewed") state.append(element("span", `· ${REVIEW_LABELS[review]}`, "review-indicator"));
      bottom.append(state, element("span", distanceText(building.distance))); button.append(title, meta, bottom);
      button.addEventListener("click", () => selectBuilding(building, true)); fragment.append(button);
    }
    $("building-list").replaceChildren(fragment);
    $("empty-results").hidden = filtered.length !== 0;
    $("result-count").textContent = `${number(filtered.length)}개 건물`;
    $("page-label").textContent = `${page + 1} / ${pages}`;
    $("previous-page").disabled = page === 0; $("next-page").disabled = page + 1 >= pages;
  }
  function applyFilters(resetPage = true) {
    if (resetPage) { page = 0; $("building-list").scrollTop = 0; }
    const status = $("status-filter").value, radius = $("radius-filter").value;
    const query = $("search").value.trim().toLocaleLowerCase("ko-KR"), review = $("review-filter").value;
    filtered = buildings.filter(building => (radius === "all" || building.distance <= Number(radius)) &&
      (status === "all" || status === "known" && building.height !== null || status === "unknown" && building.height === null ||
       status === "conflict" && building.conflict || status === "verified" && building.audit.pkValidation === "VERIFIED_OFFICIAL_PK") &&
      (review === "all" || (notes.get(building.id)?.status || "unreviewed") === review) && (!query || building.search.includes(query)));
    visibleIds = new Set(filtered.map(building => building.id));
    for (const button of document.querySelectorAll("[data-quick]")) button.classList.toggle("active", button.dataset.quick === status);
    renderList(); requestDraw();
  }
  function resetFilters() {
    if (currentPilot) { activatePilot(""); return; }
    closeInspector(); $("search").value = ""; $("status-filter").value = "all"; $("radius-filter").value = "500"; $("review-filter").value = "all";
    applyFilters(); fitBounds(routeBounds, 540);
  }
  const worldToScreen = point => [(point[0] - view.x) * view.scale + view.width / 2, view.height / 2 - (point[1] - view.y) * view.scale];
  const screenToWorld = point => [(point[0] - view.width / 2) / view.scale + view.x, (view.height / 2 - point[1]) / view.scale + view.y];
  function fitBounds(box, padding = 50, inspector = false) {
    const overlay = inspector ? $("inspector").offsetWidth : 0;
    const width = Math.max(100, view.width - overlay - 55), height = Math.max(100, view.height - 110);
    view.scale = Math.min(12, Math.max(.025, Math.min(width / (box[2] - box[0] + padding * 2), height / (box[3] - box[1] + padding * 2))));
    view.x = (box[0] + box[2]) / 2 + overlay / (2 * view.scale); view.y = (box[1] + box[3]) / 2; requestDraw();
  }
  function zoom(factor, screen = [view.width / 2, view.height / 2]) {
    const before = screenToWorld(screen); view.scale = Math.max(.025, Math.min(20, view.scale * factor));
    const after = screenToWorld(screen); view.x += before[0] - after[0]; view.y += before[1] - after[1]; requestDraw();
  }
  function requestDraw() { if (!drawPending) { drawPending = true; requestAnimationFrame(() => { drawPending = false; draw(); }); } }
  function drawLine(points) { ctx.beginPath(); points.forEach((point, index) => index ? ctx.lineTo(...point) : ctx.moveTo(...point)); }
  function draw() {
    ctx.setTransform(view.dpr, 0, 0, view.dpr, 0, 0); ctx.clearRect(0, 0, view.width, view.height);
    ctx.fillStyle = "#edf0e8"; ctx.fillRect(0, 0, view.width, view.height);
    const low = screenToWorld([0, view.height]), high = screenToWorld([view.width, 0]);
    const viewport = [...low, ...high];
    ctx.translate(view.width / 2, view.height / 2); ctx.scale(view.scale, -view.scale); ctx.translate(-view.x, -view.y);
    const step = view.scale > .6 ? 100 : 500;
    ctx.strokeStyle = "#e4e9df"; ctx.lineWidth = .6 / view.scale;
    ctx.beginPath();
    for (let x = Math.floor(low[0] / step) * step; x < high[0]; x += step) { ctx.moveTo(x, low[1]); ctx.lineTo(x, high[1]); }
    for (let y = Math.floor(low[1] / step) * step; y < high[1]; y += step) { ctx.moveTo(low[0], y); ctx.lineTo(high[0], y); }
    ctx.stroke();
    ctx.lineCap = "round"; ctx.lineJoin = "round";
    for (const road of data.roads) if (intersectsBox(road.box, viewport)) {
      drawLine(road.projected); ctx.strokeStyle = "#d7dfd2"; ctx.lineWidth = (road.kind === "footway" || road.kind === "path" ? 2.5 : 5) / view.scale; ctx.stroke();
      ctx.strokeStyle = "#fcfdf9"; ctx.lineWidth = (road.kind === "footway" || road.kind === "path" ? 1.4 : 3.4) / view.scale; ctx.stroke();
    }
    for (const building of buildings) if (intersectsBox(building.box, viewport)) {
      const active = visibleIds.has(building.id);
      ctx.globalAlpha = active ? .85 : .17;
      ctx.fillStyle = building.conflict ? "#d46c61" : building.height === null ? "#e1b359" : "#70a992";
      ctx.strokeStyle = building.conflict ? "#b54d44" : building.height === null ? "#c79c4b" : "#5b927d";
      ctx.lineWidth = (building.conflict ? 1 : .45) / view.scale;
      ctx.fill(building.path, "evenodd"); ctx.stroke(building.path);
    }
    ctx.globalAlpha = 1;
    if (currentPilot) {
      for (const [kind, color, radius] of [["inspection", "#a5a07e", data.pilotPlan.inspectionRadius], ["audit", "#b78a36", data.pilotPlan.auditRadius], ["core", "#537bb3", data.pilotPlan.coreRadius]]) {
        ctx.strokeStyle = color; ctx.lineWidth = 1.4 / view.scale; ctx.setLineDash([6 / view.scale, 4 / view.scale]); ctx.stroke(currentPilot.paths[kind]);
      }
      ctx.setLineDash([]);
      for (const outline of currentPilot.osmOutlines) {
        ctx.strokeStyle = "#8c65b0"; ctx.lineWidth = 2 / view.scale; ctx.setLineDash([4 / view.scale, 3 / view.scale]); ctx.stroke(outline.path);
      }
      ctx.setLineDash([]);
    }
    drawLine(route); ctx.strokeStyle = "#fff"; ctx.lineWidth = 8 / view.scale; ctx.stroke();
    ctx.strokeStyle = "#277b61"; ctx.lineWidth = 3.6 / view.scale; ctx.stroke();
    if (selected) { ctx.strokeStyle = "#fff"; ctx.lineWidth = 5 / view.scale; ctx.stroke(selected.path); ctx.strokeStyle = "#214f41"; ctx.lineWidth = 2.3 / view.scale; ctx.stroke(selected.path); }
    ctx.setTransform(view.dpr, 0, 0, view.dpr, 0, 0);
    if (currentPilot) for (const outline of currentPilot.osmOutlines) {
      const point = worldToScreen([(outline.box[0] + outline.box[2]) / 2, outline.box[3]]);
      ctx.font = '9px "Malgun Gothic",sans-serif'; ctx.textAlign = "center"; ctx.fillStyle = "#79529c";
      ctx.fillText(outline.id.replace("osm:way/", "OSM "), point[0], point[1] - 4);
    }
    const placedLabels = [];
    if (view.scale > .22) for (const road of data.roads) if (road.name && intersectsBox(road.box, viewport)) {
      const point = worldToScreen(road.projected[Math.floor(road.projected.length / 2)]);
      if (point[0] < 25 || point[0] > view.width - 60 || point[1] < 80 || point[1] > view.height - 65 || placedLabels.some(p => Math.abs(p[0] - point[0]) < 105 && Math.abs(p[1] - point[1]) < 42)) continue;
      ctx.font = '10px "Malgun Gothic",sans-serif'; ctx.textAlign = "center"; ctx.lineWidth = 3; ctx.strokeStyle = "#f4f6eef0"; ctx.strokeText(road.name, ...point); ctx.fillStyle = "#8a9781"; ctx.fillText(road.name, ...point); placedLabels.push(point);
    }
    for (const [index, point] of [route[0], route[route.length - 1]].entries()) {
      const [x, y] = worldToScreen(point); ctx.beginPath(); ctx.arc(x, y, 9, 0, Math.PI * 2); ctx.fillStyle = index ? "#20382f" : "#fff"; ctx.fill(); ctx.strokeStyle = "#277b61"; ctx.lineWidth = 2; ctx.stroke();
      ctx.fillStyle = index ? "#fff" : "#277b61"; ctx.font = "bold 9px sans-serif"; ctx.textAlign = "center"; ctx.textBaseline = "middle"; ctx.fillText(index ? "도" : "출", x, y + .5);
    }
    ctx.textBaseline = "alphabetic";
    const target = 95 / view.scale, magnitude = 10 ** Math.floor(Math.log10(target));
    const scaleMeters = [1, 2, 5, 10].map(n => n * magnitude).filter(n => n <= target).pop() || magnitude;
    $("map-scale").textContent = `${number(scaleMeters)}m`;
  }
  function resize() {
    const rect = canvas.getBoundingClientRect(); view.width = rect.width; view.height = rect.height; view.dpr = Math.min(2, window.devicePixelRatio || 1);
    canvas.width = Math.round(rect.width * view.dpr); canvas.height = Math.round(rect.height * view.dpr); requestDraw();
  }
  function polygonPath(polygons) {
    const path = new Path2D();
    for (const polygon of polygons) for (const ring of polygon) { ring.forEach((point, index) => index ? path.lineTo(...point) : path.moveTo(...point)); path.closePath(); }
    return path;
  }
  function updateRouteCaption() {
    const info = currentPilot ? currentPilot.route : data.route;
    $("route-title").textContent = currentPilot ? `${currentPilot.name} · 실제 서버 보행 경로` : "시험 보행 경로";
    $("route-summary").textContent = `${number(info.distanceMeters)}m · ${Math.floor(info.durationSeconds / 60)}분 ${info.durationSeconds % 60}초 · 출 → 도`;
  }
  function activatePilot(id) {
    closeInspector();
    currentPilot = data.pilotPlan?.candidates.find(candidate => candidate.id === id) || null;
    $("pilot-select").value = currentPilot?.id || "";
    history.replaceState(null, "", currentPilot ? `#pilot=${encodeURIComponent(currentPilot.id)}` : "#");
    route = currentPilot ? currentPilot.route.coordinates.map(project) : baseRoute;
    routeSegments = route.slice(1).map((point, index) => [route[index], point]);
    for (const building of buildings) building.distance = routeDistance(building);
    buildings.sort((a, b) => priority(a) - priority(b) || a.distance - b.distance || a.id.localeCompare(b.id));
    $("radius-label").textContent = currentPilot ? "후보 중심과 거리" : "시험 경로와 거리";
    $("search").value = ""; $("status-filter").value = "all"; $("review-filter").value = "all";
    $("radius-filter").value = currentPilot ? String(data.pilotPlan.auditRadius) : "500";
    const panel = $("pilot-summary"); panel.hidden = !currentPilot; panel.replaceChildren();
    if (currentPilot) {
      panel.append(element("span", "평가 보류 · 아직 그늘 비율 미제공", "badge unknown"),
        element("p", `핵심 ${data.pilotPlan.coreRadius}m · 우선 검토 ${data.pilotPlan.auditRadius}m · 주변 확인 ${data.pilotPlan.inspectionRadius}m`),
        element("p", `우선 반경: 건물 ${currentPilot.totalAudit}개 / 높이 누락 ${currentPilot.unknownAudit}개`),
        element("p", `주변 반경: 높이 미확인 ${currentPilot.unknownInspection}개 · OSM 재대조 ${currentPilot.osmReviewCount}개`),
        element("p", "파랑=핵심 · 황토=우선 검토 · 회색=주변 확인 · 보라=OSM. 거리 경계는 그림자 안전 범위가 아닙니다."));
      if (currentPilot.addressReference) panel.append(element("p", `추가 대조: 주소 건물 ${currentPilot.addressReference.addressRows}행 / GIS ${currentPilot.addressReference.publicRows}행 · 형상 차이 확인 필요`));
      const action = element("button", "주변 미확인 건물 살펴보기");
      action.addEventListener("click", () => {
        $("status-filter").value = "unknown"; $("radius-filter").value = String(data.pilotPlan.inspectionRadius);
        applyFilters(); fitBounds(currentPilot.inspectionBox, 45);
      }); panel.append(action);
    }
    updateRouteCaption(); applyFilters();
    fitBounds(currentPilot ? currentPilot.auditBox : routeBounds, currentPilot ? 90 : 540);
  }
  const grid = new Map(), CELL = 150;
  function mapClick(screen) {
    const point = screenToWorld(screen), key = `${Math.floor(point[0] / CELL)},${Math.floor(point[1] / CELL)}`;
    const hits = (grid.get(key) || []).filter(building => visibleIds.has(building.id) && inBuilding(point, building)).sort((a, b) => priority(a) - priority(b));
    if (hits.length) selectBuilding(hits[0], false, hits);
  }
  let drag = null;
  canvas.addEventListener("pointerdown", event => {
    canvas.focus(); canvas.setPointerCapture(event.pointerId); drag = {startX: event.clientX, startY: event.clientY, lastX: event.clientX, lastY: event.clientY};
    canvas.classList.add("dragging");
  });
  canvas.addEventListener("pointermove", event => {
    if (!drag) return; view.x -= (event.clientX - drag.lastX) / view.scale; view.y += (event.clientY - drag.lastY) / view.scale;
    drag.lastX = event.clientX; drag.lastY = event.clientY; requestDraw();
  });
  canvas.addEventListener("pointerup", event => {
    if (!drag) return; const moved = Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY); drag = null; canvas.classList.remove("dragging");
    if (moved < 5) { const rect = canvas.getBoundingClientRect(); mapClick([event.clientX - rect.left, event.clientY - rect.top]); }
  });
  canvas.addEventListener("pointercancel", () => { drag = null; canvas.classList.remove("dragging"); });
  canvas.addEventListener("wheel", event => { event.preventDefault(); const rect = canvas.getBoundingClientRect(); zoom(Math.exp(-event.deltaY * .0015), [event.clientX - rect.left, event.clientY - rect.top]); }, {passive: false});
  canvas.addEventListener("keydown", event => {
    if (["+", "=", "-", "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Escape"].includes(event.key)) event.preventDefault();
    if (event.key === "+" || event.key === "=") zoom(1.4); else if (event.key === "-") zoom(1 / 1.4);
    else if (event.key === "ArrowLeft") view.x -= 70 / view.scale; else if (event.key === "ArrowRight") view.x += 70 / view.scale;
    else if (event.key === "ArrowUp") view.y += 70 / view.scale; else if (event.key === "ArrowDown") view.y -= 70 / view.scale;
    else if (event.key === "Escape") closeInspector(); requestDraw();
  });
  $("zoom-in").addEventListener("click", () => zoom(1.5)); $("zoom-out").addEventListener("click", () => zoom(1 / 1.5));
  $("fit-all").addEventListener("click", () => { closeInspector(); fitBounds(allBounds, 60); });
  $("route-focus").addEventListener("click", resetFilters); $("clear-filters").addEventListener("click", resetFilters);
  $("pilot-select").addEventListener("change", event => activatePilot(event.target.value));
  document.querySelector(".brand").addEventListener("click", event => { event.preventDefault(); activatePilot(""); });
  window.addEventListener("hashchange", () => {
    const id = new URLSearchParams(location.hash.slice(1)).get("pilot") || "";
    if (id !== (currentPilot?.id || "")) activatePilot(id);
  });
  $("close-inspector").addEventListener("click", closeInspector);
  $("next-overlap").addEventListener("click", () => { const next = overlaps[(overlaps.indexOf(selected) + 1) % overlaps.length]; if (next) selectBuilding(next, false, overlaps); });
  for (const id of ["status-filter", "radius-filter", "review-filter"]) $(id).addEventListener("change", () => { flushNote(); applyFilters(); });
  $("search").addEventListener("input", () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => applyFilters(), 160); });
  for (const button of document.querySelectorAll("[data-quick]")) button.addEventListener("click", () => {
    closeInspector(); $("search").value = ""; $("review-filter").value = "all"; $("status-filter").value = button.dataset.quick; $("radius-filter").value = "all";
    applyFilters(); if (button.dataset.quick === "conflict" && filtered.length) fitBounds(bounds(filtered.flatMap(b => [[b.box[0], b.box[1]], [b.box[2], b.box[3]]])), 150); else fitBounds(allBounds, 60);
  });
  $("previous-page").addEventListener("click", () => { page--; renderList(); $("building-list").scrollTop = 0; });
  $("next-page").addEventListener("click", () => { page++; renderList(); $("building-list").scrollTop = 0; });
  $("note-status").addEventListener("change", scheduleNote); $("note-text").addEventListener("input", scheduleNote); $("save-note").addEventListener("click", () => saveNote(true));
  window.addEventListener("pagehide", flushNote);
  $("export-notes").addEventListener("click", () => {
    flushNote(); const blob = new Blob([JSON.stringify(noteBundle(), null, 2)], {type: "application/json"});
    const url = URL.createObjectURL(blob), link = element("a"); link.href = url; link.download = `igilro-building-notes-${data.dataVersion.slice(-10)}.json`; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 2000); toast(`${notes.size}개 검수 메모를 내보냈습니다.`);
  });
  $("import-notes").addEventListener("click", () => $("notes-file").click());
  $("notes-file").addEventListener("change", async event => {
    const file = event.target.files[0]; if (!file) return;
    try {
      if (file.size > 100_000_000) throw new Error("메모 파일은 100MB 이하만 가져올 수 있습니다.");
      const incoming = validateNotes(JSON.parse(await file.text())); flushNote();
      for (const [id, row] of incoming) if (!notes.has(id) || Date.parse(row.updatedAt) > Date.parse(notes.get(id).updatedAt)) notes.set(id, row);
      persistNotes(); applyFilters(false); if (selected) selectBuilding(selected, false, overlaps); toast(`${incoming.size}개 메모를 확인하고 최신 내용을 병합했습니다.`);
    } catch (error) { toast(error instanceof SyntaxError ? "올바른 JSON 메모 파일이 아닙니다." : error.message || "메모를 가져오지 못했습니다."); }
    event.target.value = "";
  });
  let allBounds = [Infinity, Infinity, -Infinity, -Infinity];
  function initialize() {
    for (const building of buildings) {
      building.projected = building.polygons.map(polygon => polygon.map(ring => ring.map(project)));
      building.path = new Path2D();
      for (const polygon of building.projected) for (const ring of polygon) { ring.forEach((point, index) => index ? building.path.lineTo(...point) : building.path.moveTo(...point)); building.path.closePath(); }
      building.box = bounds(building.projected.flat(2));
      allBounds = [Math.min(allBounds[0], building.box[0]), Math.min(allBounds[1], building.box[1]), Math.max(allBounds[2], building.box[2]), Math.max(allBounds[3], building.box[3])];
      building.distance = routeDistance(building);
      building.search = [shortName(building), building.id, building.pnu, building.gis.buld_idntfc_no, building.audit.convertedHubPk,
        building.audit.hubSourceId, building.hub?.newPlatPlc, building.hub?.platPlc].filter(Boolean).join(" ").toLocaleLowerCase("ko-KR");
      for (let x = Math.floor(building.box[0] / CELL); x <= Math.floor(building.box[2] / CELL); x++) for (let y = Math.floor(building.box[1] / CELL); y <= Math.floor(building.box[3] / CELL); y++) {
        const key = `${x},${y}`; if (!grid.has(key)) grid.set(key, []); grid.get(key).push(building);
      }
    }
    buildings.sort((a, b) => priority(a) - priority(b) || a.distance - b.distance || a.id.localeCompare(b.id));
    for (const road of data.roads) { road.projected = road.coordinates.map(project); road.box = bounds(road.projected); }
    for (const key of ["all", "known", "unknown", "conflicts"]) $(`count-${key}`).textContent = number(data.counts[key]);
    $("snapshot-time").textContent = `원본 응답 관측 ${new Intl.DateTimeFormat("ko-KR", {timeZone: "Asia/Seoul", dateStyle: "medium", timeStyle: "short"}).format(new Date(data.sourceTimestamp))}`;
    updateRouteCaption();
    if (data.pilotPlan) {
      $("pilot-controls").hidden = false;
      for (const radius of [data.pilotPlan.auditRadius, data.pilotPlan.inspectionRadius]) {
        if (![...$("radius-filter").options].some(option => option.value === String(radius))) {
          const option = element("option", `${number(radius)}m 이내`); option.value = String(radius); $("radius-filter").append(option);
        }
      }
      for (const candidate of data.pilotPlan.candidates) {
        candidate.projectedCenter = project(candidate.center); candidate.paths = {};
        for (const key of ["core", "audit", "inspection"]) {
          const projected = candidate[key].map(polygon => polygon.map(ring => ring.map(project)));
          candidate.paths[key] = polygonPath(projected);
          candidate[`${key}Box`] = bounds(projected.flat(2));
        }
        for (const outline of candidate.osmOutlines) {
          const projected = outline.polygons.map(polygon => polygon.map(ring => ring.map(project)));
          outline.path = polygonPath(projected); outline.box = bounds(projected.flat(2));
        }
        const option = element("option", `${candidate.name} · 평가 보류 · ${candidate.totalAudit}개 중 높이 누락 ${candidate.unknownAudit}개`);
        option.value = candidate.id; $("pilot-select").append(option);
      }
    }
    $("version-label").textContent = `건물 버전 ${data.dataVersion.slice(0, 25)}… · ${data.counts.pkVerified.toLocaleString("ko-KR")}개 PK 대조 일치`;
    resize(); fitBounds(routeBounds, 540); applyFilters();
    const bookmarkedPilot = new URLSearchParams(location.hash.slice(1)).get("pilot");
    if (bookmarkedPilot && data.pilotPlan?.candidates.some(candidate => candidate.id === bookmarkedPilot)) activatePilot(bookmarkedPilot);
    $("map-loading").hidden = true;
    if (!storageOkay) toast("이 브라우저에서 저장된 메모를 읽지 못했습니다. JSON 내보내기를 이용해 주세요.");
    document.body.dataset.ready = "true";
  }
  new ResizeObserver(resize).observe(canvas);
  requestAnimationFrame(() => requestAnimationFrame(() => {
    try { initialize(); } catch { $("map-loading").textContent = "지도를 준비하지 못했습니다. 검증된 스냅샷으로 HTML을 다시 생성해 주세요."; }
  }));
})();
