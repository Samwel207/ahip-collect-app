const L = require("./lib");
const { str, dec, date, phone, note, page, group, region, council } = L;

// [no, indicator, unit, numerator label, denominator label | null, data source, kibahaOnly]
const INDICATORS = [
  [1, "Adolescent pregnancy rate (percentage of women aged 10-19 years) in selected district councils", "Percentage (%)", "No. of pregnancies among girls aged 10-19", "Relevant denominator used by source", "DHIS2"],
  [2, "Under-five mortality rate in selected district councils", "Per 1,000 live births", "No. of deaths among children under five", "No. of live births", "DHIS2"],
  [3, "Neonatal mortality rate in selected district council", "Per 1,000 live births", "No. of neonatal deaths (0-28 days)", "No. of live births", "DHIS2"],
  [4, "Maternal mortality rate in selected district councils", "Per 100,000 live births", "No. of maternal deaths", "No. of live births", "DHIS2"],
  [5, "Number of new tuberculosis cases occurring per 100,000 population per year", "Per 100,000 population", "No. of newly identified TB cases", "Total population", "ETL - DHIS2"],
  [6, "Number of TB cases recorded at health facilities", "Number", "Actual number of TB cases", null, "ETL - DHIS2"],
  [7, "Number of TB cases at the health facilities referred by CHWs", "Number", "Actual number of TB cases referred by CHWs", null, "UCS dashboard"],
  [8, "Number of mental health cases recorded at facilities", "Number", "Actual number of mental health cases", null, "Social Welfare Office"],
  [9, "Number of RoC referred by CHWs and receiving diabetes care at health facilities (Kibaha Municipal only)", "Number", "Actual number of clients receiving diabetes care at the HF", null, "GoTHOMIS", true],
  [10, "Number of RoC referred by CHWs and receiving hypertension care at health facilities (Kibaha Municipal only)", "Number", "Actual number of clients receiving hypertension care at the HF", null, "GoTHOMIS", true],
];

const pad = (n) => String(n).padStart(2, "0");

function indicator([n, text, unit, numerator, denominator, source, kibahaOnly]) {
  const id = `ind${pad(n)}`;
  const sourceItem = str(`${id}_source`, "Data source")[0];
  sourceItem.initial = [{ valueString: source }];
  return group(id, `${text} — Unit: ${unit}`, { n, ...(kibahaOnly ? { when: ["council", "kibaha_mc"] } : {}) },
    dec(`${id}_numerator`, `Numerator / actual number: ${numerator}`, { req: true }),
    denominator ? dec(`${id}_denominator`, `Denominator: ${denominator}`) : [],
    dec(`${id}_baseline`, "Verified baseline value", { req: true }),
    [sourceItem],
    str(`${id}_comments`, "Comments / verification", { long: true }),
  );
}

const period = str("reporting_period", "Reporting period confirmed")[0];
period.initial = [{ valueString: "July 2025 - June 2026" }];

module.exports = L.questionnaire({
  id: "ahip-t4-routine-indicators",
  title: "Tool 4: High-Level Routine Indicators",
  description: "Afya-Tek Phase III Baseline Assessment — Baseline Indicator Data Collection Tool. Reporting timeline: 1 July 2025 to 30 June 2026.",
  pages: [
    page("s0", "Data Collection Details",
      note("purpose", "Purpose: collect actual baseline values for selected Phase III indicators from routine data sources for July 2025 - June 2026. Enter the actual number, numerator/denominator where applicable, and the verified baseline value."),
      region(),
      council("District Council"),
      date("collection_date", "Date of data collection"),
      str("collector", "Name of data collector", { req: true }),
      str("position", "Position/Office"),
      phone("collector_phone", "Phone number"),
    ),
    page("s1", "Section 1: Baseline Indicator Values",
      note("s1_note", "Record values exactly as reported in the identified source. For rate/percentage indicators capture both numerator and denominator where available. For count indicators, enter the actual count and repeat the verified figure as the baseline value."),
      ...INDICATORS.map(indicator),
    ),
    page("s2", "Section 2: Data Verification",
      str("source_document", "Source document/report reviewed", { req: true }),
      [period],
      str("verifier_name", "Name of person verifying data", { req: true }),
      str("verifier_designation", "Designation"),
      date("verification_date", "Date"),
    ),
  ],
});
