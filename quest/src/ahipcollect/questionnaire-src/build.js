// Usage: node build.js <outDir> [t1 t2 ...]
// Builds each tool's Questionnaire, validates it, and writes <outDir>/<file>.json.
const fs = require("fs");
const path = require("path");

const FILES = {
  t1: "ahip_t1_cpp_provider.json",
  t2: "ahip_t2_healthcare_worker.json",
  t3: "ahip_t3_exit_client.json",
  t4: "ahip_t4_routine_indicators.json",
  t5: "ahip_t5_cpp_digital_sustainability.json",
  t6: "ahip_t6_facility_digital_sustainability.json",
};

const outDir = process.argv[2];
const tools = process.argv.slice(3).length ? process.argv.slice(3) : Object.keys(FILES);
let failed = false;

for (const t of tools) {
  const q = require(path.join(__dirname, `${t}.js`));
  const all = [];
  const walk = (items) => items.forEach((it) => { all.push(it); walk(it.item || []); });
  walk(q.item);

  const errors = [];
  const byId = new Map();
  for (const it of all) {
    if (byId.has(it.linkId)) errors.push(`duplicate linkId ${it.linkId}`);
    byId.set(it.linkId, it);
  }
  for (const it of all) {
    for (const ew of it.enableWhen || []) {
      const target = byId.get(ew.question);
      if (!target) { errors.push(`${it.linkId}: enableWhen -> missing ${ew.question}`); continue; }
      const codes = (target.answerOption || []).map((a) => a.valueCoding.code);
      if (!codes.includes(ew.answerCoding.code))
        errors.push(`${it.linkId}: enableWhen ${ew.question}=${ew.answerCoding.code} not in [${codes.join(", ")}]`);
    }
    if (it.type === "group" && it.extension?.some((e) => e.valueCodeableConcept?.coding?.[0]?.code === "page") && !it.item?.length)
      errors.push(`${it.linkId}: empty page`);
  }
  const numbered = all.filter((it) => it.prefix).length;
  const pages = q.item.map((p) => `${p.text} (${(p.item || []).filter((i) => i.prefix).length})`);

  console.log(`\n${t}: ${q.title} — ${numbered} numbered questions, ${all.length} items, ${q.item.length} pages`);
  pages.forEach((p) => console.log("   - " + p));
  if (errors.length) { failed = true; errors.forEach((e) => console.log("   ERROR " + e)); }
  else fs.writeFileSync(path.join(outDir, FILES[t]), JSON.stringify(q, null, 2) + "\n");
}
process.exit(failed ? 1 : 0);
