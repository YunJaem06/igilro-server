"use strict";
const BuildingReviewNotes = Object.freeze({
  validate(bundle, dataVersion, buildingsById, statuses) {
    if (!bundle || bundle.schemaVersion !== 1 || bundle.dataVersion !== dataVersion || !Array.isArray(bundle.records) || bundle.records.length > buildingsById.size) {
      throw new Error("현재 건물 버전의 검수 메모 JSON이 필요합니다.");
    }
    const result = new Map();
    for (const row of bundle.records) {
      if (!row || !buildingsById.has(row.id) || result.has(row.id) || !Object.hasOwn(statuses, row.status) ||
          typeof row.note !== "string" || row.note.length > 2000 || typeof row.updatedAt !== "string" || !Number.isFinite(Date.parse(row.updatedAt))) {
        throw new Error("메모 파일에 유효하지 않거나 중복된 항목이 있습니다.");
      }
      // Imports can only change these annotation fields, never geometry or height.
      result.set(row.id, {id: row.id, status: row.status, note: row.note, updatedAt: row.updatedAt});
    }
    return result;
  }
});
if (typeof module !== "undefined") module.exports = BuildingReviewNotes;
