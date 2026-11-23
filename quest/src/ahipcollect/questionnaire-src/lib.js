// Helpers that build FHIR R4 Questionnaire items for the AHIP tools.
// Main (numbered) questions get a `prefix` so the app can count them.

const SYS = "urn:ahip:answer";
const ITEM_CONTROL = "http://hl7.org/fhir/StructureDefinition/questionnaire-itemControl";
const CONTROL_SYS = "http://hl7.org/fhir/questionnaire-item-control";
const HIDDEN = "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden";
const MIN_VALUE = "http://hl7.org/fhir/StructureDefinition/minValue";
const MAX_VALUE = "http://hl7.org/fhir/StructureDefinition/maxValue";
const GPS = "https://github.com/google/android-fhir/StructureDefinition/gps-coordinate";

const control = (code) => ({
  url: ITEM_CONTROL,
  valueCodeableConcept: { coding: [{ system: CONTROL_SYS, code }] },
});

const slug = (s) =>
  s
    .toLowerCase()
    .replace(/\(.*?\)/g, " ")
    .replace(/[’'`]/g, "")
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, 48)
    .replace(/_+$/g, "") || "option";

const isOther = (s) => /^others?\b/i.test(s.trim());
const clean = (s) => s.replace(/[_.…]{3,}.*$/, "").replace(/:\s*$/, "").replace(/\s+/g, " ").trim();

function options(list) {
  const used = new Set();
  return list.map((raw) => {
    let display = clean(raw);
    let code = isOther(display) ? "other" : slug(display);
    if (isOther(display)) display = "Other (specify)";
    let c = code, n = 2;
    while (used.has(c)) c = `${code}_${n++}`;
    used.add(c);
    return { valueCoding: { system: SYS, code: c, display } };
  });
}

// when: ["linkId", "code"] or [["id","code"], ["id","code2"]] (any of)
function enableWhen(when) {
  if (!when) return {};
  const list = Array.isArray(when[0]) ? when : [when];
  const conds = list.map(([question, code]) => ({
    question,
    operator: "=",
    answerCoding: { system: SYS, code },
  }));
  return conds.length > 1 ? { enableWhen: conds, enableBehavior: "any" } : { enableWhen: conds };
}

function base(linkId, text, type, o = {}) {
  const item = { linkId, ...(o.n != null ? { prefix: `${o.n}.` } : {}), text, type };
  if (o.req) item.required = true;
  Object.assign(item, enableWhen(o.when));
  return item;
}

function specify(parentId, parentText, o) {
  return {
    linkId: `${parentId}_other`,
    text: "Other — please specify",
    type: "string",
    enableWhen: [{ question: parentId, operator: "=", answerCoding: { system: SYS, code: "other" } }],
  };
}

/** Single-answer question (radio buttons, or a drop-down for long lists). */
function choice(id, text, opts, o = {}) {
  const item = base(id, text, "choice", { req: true, ...o });
  item.answerOption = options(opts);
  item.extension = [control(opts.length > 9 ? "drop-down" : "radio-button")];
  if (o.initial) {
    // FHIR rule que-11: preselect via answerOption.initialSelected, not item.initial.
    item.answerOption.find((a) => a.valueCoding.code === o.initial).initialSelected = true;
  }
  if (o.readOnly) item.readOnly = true;
  const out = [item];
  if (item.answerOption.some((a) => a.valueCoding.code === "other")) out.push(specify(id, text, o));
  return out;
}

/** Select-all-that-apply question (check boxes). */
function multi(id, text, opts, o = {}) {
  const item = base(id, text, "choice", { req: true, ...o });
  item.repeats = true;
  item.answerOption = options(opts);
  item.extension = [control("check-box")];
  const out = [item];
  if (item.answerOption.some((a) => a.valueCoding.code === "other")) out.push(specify(id, text, o));
  return out;
}

const yesNo = (id, text, o = {}) => choice(id, text, ["Yes", "No"], o);
const yesNoDk = (id, text, o = {}) => choice(id, text, ["Yes", "No", "I don't know"], o);

/**
 * Free-text answer. Open-ended questions (numbered or long) also get an optional audio recording
 * of the spoken answer (pass `audio: false` to leave it out).
 */
function str(id, text, o = {}) {
  const item = base(id, text, o.long ? "text" : "string", o);
  const openEnded = o.n != null || o.long;
  return openEnded && o.audio !== false ? [item, audio(`${id}_audio`, item)] : [item];
}

/**
 * Audio answer: Record / Play / Delete buttons (item control `audio-recorder`, see
 * AudioRecorderViewHolderFactory in the app). Shown whenever its question is shown.
 */
function audio(id, question, o = {}) {
  const minutes = o.minutes ?? 5;
  const item = {
    linkId: id,
    text: o.text ?? "Audio recording of the answer (optional)",
    type: "attachment",
    extension: [
      control("audio-recorder"),
      { url: "http://hl7.org/fhir/StructureDefinition/mimeType", valueCode: "audio/mp4" },
      // ~0.25 MB per minute; bytes, with headroom.
      { url: "http://hl7.org/fhir/StructureDefinition/maxSize", valueDecimal: Math.max(15, minutes) * 1024 * 1024 },
      { url: "https://smartregister.org/audio-max-duration-minutes", valueInteger: minutes },
    ],
  };
  if (o.required) item.required = true;
  if (question?.enableWhen) {
    item.enableWhen = question.enableWhen;
    if (question.enableBehavior) item.enableBehavior = question.enableBehavior;
  }
  return item;
}

function int(id, text, o = {}) {
  const item = base(id, text, "integer", o);
  item.extension = [{ url: MIN_VALUE, valueInteger: o.min ?? 0 }];
  if (o.max != null) item.extension.push({ url: MAX_VALUE, valueInteger: o.max });
  return [item];
}

function dec(id, text, o = {}) {
  const item = base(id, text, "decimal", o);
  item.extension = [{ url: MIN_VALUE, valueDecimal: 0 }];
  return [item];
}

function date(id, text, o = {}) {
  return [base(id, text, "date", { req: true, ...o })];
}

function phone(id, text = "Phone number of the interviewee (+255)") {
  const item = base(id, text, "string", {});
  item.extension = [{ url: "http://hl7.org/fhir/StructureDefinition/regex", valueString: "^(\\+?255|0)[0-9]{9}$" }];
  return [item];
}

/**
 * GPS reading: a "Record GPS Location" button (item control `location-widget`) that asks for
 * location permission, gets a fix and fills the latitude / longitude fields inside the group.
 */
function gps(prefix = "gps") {
  const coord = (axis) => ({
    linkId: `${prefix}_${axis}`,
    text: axis === "latitude" ? "Latitude" : "Longitude",
    type: "decimal",
    extension: [{ url: GPS, valueString: axis }],
  });
  return [
    {
      linkId: prefix,
      text: "GPS reading",
      type: "group",
      extension: [control("location-widget")],
      item: [coord("latitude"), coord("longitude")],
    },
  ];
}

const SCALE6 = ["1 - Strongly disagree", "2 - Disagree", "3 - Neutral", "4 - Agree", "5 - Strongly agree", "6 - I don't know"];
const SCALE5 = SCALE6.slice(0, 5);

function likert(id, text, scale, o = {}) {
  const item = base(id, text, "choice", { req: true, ...o });
  item.answerOption = scale.map((d, i) => ({ valueCoding: { system: SYS, code: String(i + 1), display: d } }));
  item.extension = [control("radio-button")];
  return [item];
}

function note(id, text) {
  return [{ linkId: id, text, type: "display" }];
}

/** A section shown as one page with Previous / Next. */
function page(id, title, ...items) {
  return { linkId: id, text: title, type: "group", extension: [control("page")], item: items.flat() };
}

/** Indicator block (Tool 4): counted as one numbered item. */
function group(id, text, o, ...items) {
  const g = base(id, text, "group", o);
  g.item = items.flat();
  return [g];
}

const hiddenStudyFields = () =>
  ["ahip-study", "ahip-assessment"].map((linkId) => ({
    linkId,
    text: linkId === "ahip-study" ? "Study" : "Assessment",
    type: "string",
    readOnly: true,
    extension: [{ url: HIDDEN, valueBoolean: true }],
  }));

// Councils covered by Afya-Tek Phase III, grouped by region.
const COUNCILS_BY_REGION = {
  Dodoma: ["Chemba DC"],
  Tanga: ["Tanga CC", "Pangani DC", "Handeni DC"],
  Ruvuma: ["Songea MC", "Songea DC"],
  Njombe: ["Njombe TC", "Makambako DC"],
  Pwani: ["Kibaha MC", "Rufiji DC"],
  Mbeya: ["Mbeya CC", "Busokelo DC"],
  Tabora: ["Nzega DC"],
  Lindi: ["Mtama DC"],
};
const REGIONS = Object.keys(COUNCILS_BY_REGION);
const COUNCILS = Object.values(COUNCILS_BY_REGION).flat();
const TOGGLE = "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-answerOptionsToggleExpression";

const NOT_LISTED = "Other (not in list)";

/**
 * A drop-down with the given system, plus "Other (not in list)" which reveals a box to type the
 * name — so places missing from the list can still be recorded.
 */
function placeChoice(linkId, text, names, system, otherText, o) {
  const item = choice(linkId, text, [...names, "Other"], o)[0];
  item.answerOption.forEach((a) => (a.valueCoding.system = system));
  const other = item.answerOption.find((a) => a.valueCoding.code === "other").valueCoding;
  other.display = NOT_LISTED;
  item.extension = [control("drop-down")];
  const typed = {
    linkId: `${linkId}_other`,
    text: otherText,
    type: "string",
    required: true,
    enableWhen: [{ question: linkId, operator: "=", answerCoding: { system, code: "other" } }],
  };
  return [item, typed];
}

function region(o = {}) {
  return placeChoice("region", "Region", REGIONS, "urn:ahip:region", "Region name (not in the list)", o);
}

/**
 * Council list that only offers the councils of the selected region. "Other (not in list)" is
 * always offered, so an unlisted region or council can be typed in.
 */
function council(text = "District / Council", o = {}) {
  const [item, typed] = placeChoice("council", text, COUNCILS, "urn:ahip:council", "District / council name (not in the list)", o);
  for (const [regionName, councils] of Object.entries(COUNCILS_BY_REGION)) {
    item.extension.push({
      url: TOGGLE,
      extension: [
        ...councils.map((name) => ({
          url: "option",
          valueCoding: item.answerOption.find((a) => a.valueCoding.display === name).valueCoding,
        })),
        {
          url: "expression",
          valueExpression: {
            language: "text/fhirpath",
            expression: `%resource.repeat(item).where(linkId='region').answer.value.code = '${slug(regionName)}'`,
          },
        },
      ],
    });
  }
  return [item, typed];
}

const MIME_TYPE = "http://hl7.org/fhir/StructureDefinition/mimeType";
const MAX_SIZE = "http://hl7.org/fhir/StructureDefinition/maxSize";

/**
 * Photo items: the form shows "Take photo" (camera) and "Upload photo" (gallery) buttons. They
 * are not numbered, so they don't change the question counts; the app counts them separately
 * (linkIds must match AhipCatalog.PHOTO_LINK_IDS).
 */
function photo(id, text, o = {}) {
  const item = base(id, text, "attachment", o);
  item.extension = [
    { url: MIME_TYPE, valueCode: "image/jpeg" },
    { url: MIME_TYPE, valueCode: "image/png" },
    { url: MAX_SIZE, valueDecimal: 10 * 1024 * 1024 }, // bytes (10 MiB); phone photos are 3–8 MB
  ];
  return [item];
}

/** Last page of every tool, as in the mockup ("Photos (2) attached"). */
const photosPage = () =>
  page("photos", "Photos",
    note("photos_note", "Take a photo of the premises / facility (e.g. the signboard or the front of the building). Add a second photo if useful."),
    photo("photo_1", "Photo 1"),
    photo("photo_2", "Photo 2 (optional)"),
  );

// ---------------------------------------------------------------- audio interview mode
// Every tool starts with a choice: answer the questions, or record the whole interview as audio
// and write a summary under it. In audio mode the questions stay available but become optional:
// the form can be submitted once the recording and the summary are done.
const MODE_ID = "collection_mode";
const AUDIO_MODE = ["Answer the questions on the phone", "Record the interview as audio"];
const AUDIO_CODE = slug(AUDIO_MODE[1]);
const whenRecording = [{ question: MODE_ID, operator: "=", answerCoding: { system: SYS, code: AUDIO_CODE } }];

function collectionMode() {
  return choice(MODE_ID, "How will you collect this interview?", AUDIO_MODE, { initial: slug(AUDIO_MODE[0]) });
}

/** Shown instead of the question sections when the interview is recorded. */
const interviewRecordingPage = () => {
  const p = page("interview_recording", "Interview recording",
    note("interview_recording_note", "Record the interview below. If it is interrupted, record the rest as Part 2. Then write what the respondent said in the box under the recording. The questions on the next pages are optional when you record: answer any you can, then submit."),
    audio("interview_audio_1", null, { text: "Interview recording (part 1)", minutes: 45, required: true }),
    audio("interview_audio_2", null, { text: "Interview recording (part 2, optional)", minutes: 45 }),
    str("interview_notes", "Summary of the recorded interview: write what the respondent said (recommended)", { long: true, audio: false }),
  );
  p.enableWhen = whenRecording;
  return p;
};

function questionnaire({ id, title, description, pages }) {
  // Hidden study / assessment fields live on the first page so pagination stays clean.
  pages[0].item = [...hiddenStudyFields(), ...collectionMode(), ...pages[0].item];
  pages.splice(1, 0, interviewRecordingPage());
  pages.push(photosPage());
  return {
    resourceType: "Questionnaire",
    id,
    name: id.replace(/-/g, "_"),
    title,
    status: "active",
    // Quest refuses to open a form without a subject type. Every AHIP tool assesses a CPP or a
    // health facility, so the subject is a Location (no subject resource is created).
    subjectType: ["Location"],
    description,
    // Recorded interviews (audio + written summary) may be submitted without the other answers.
    // Evaluated by the app on the QuestionnaireResponse (QuestionnaireViewModel).
    extension: [
      {
        url: "https://smartregister.org/allow-incomplete-submission-when",
        valueExpression: {
          language: "text/fhirpath",
          expression:
            `repeat(item).where(linkId='${MODE_ID}').answer.value.code = '${AUDIO_CODE}'` +
            ` and repeat(item).where(linkId='interview_audio_1').answer.exists()`,
        },
      },
    ],
    item: pages,
  };
}

module.exports = {
  SYS, choice, multi, yesNo, yesNoDk, str, int, dec, date, phone, gps, likert, note, page, group,
  region, council, questionnaire, SCALE5, SCALE6,
};
