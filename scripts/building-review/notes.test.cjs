const test = require("node:test");
const assert = require("node:assert/strict");
const { validate } = require("./notes.js");
const buildings = new Map([["one", {}], ["two", {}]]);
const statuses = {unreviewed: "미검토", needs_data: "추가 자료 필요"};
const row = () => ({id: "one", status: "needs_data", note: "원본 대조 필요", updatedAt: "2026-10-26T07:00:00Z"});
const bundle = records => ({schemaVersion: 1, dataVersion: "fixture-version", records});
const check = value => validate(value, "fixture-version", buildings, statuses);
test("valid annotations round-trip without importing source values", () => {
  const input = {...row(), height: 999, polygons: "untrusted", admin: true};
  const result = check(bundle([input]));
  assert.deepEqual(result.get("one"), row());
  assert.equal(input.height, 999);
  assert.equal(Object.hasOwn(result.get("one"), "height"), false);
});
test("annotations from another dataset are rejected", () => {
  assert.throws(() => check({...bundle([row()]), dataVersion: "another-version"}), /현재 건물 버전/);
});
test("unknown source identities and duplicate records are rejected atomically", () => {
  assert.throws(() => check(bundle([{...row(), id: "unknown"}])), /유효하지/);
  assert.throws(() => check(bundle([row(), row()])), /중복/);
});
test("invalid review statuses and prototype property names are rejected", () => {
  for (const status of ["admin", "__proto__", "toString"]) assert.throws(() => check(bundle([{...row(), status}])), /유효하지/);
});
test("oversized text and invalid timestamps are rejected", () => {
  assert.throws(() => check(bundle([{...row(), note: "x".repeat(2001)}])), /유효하지/);
  assert.throws(() => check(bundle([{...row(), updatedAt: "invalid"}])), /유효하지/);
});
test("empty bundles are valid and malformed or oversized record arrays are rejected", () => {
  assert.equal(check(bundle([])).size, 0);
  for (const value of [null, {}, bundle(null), bundle([row(), row(), row()])]) assert.throws(() => check(value));
});
