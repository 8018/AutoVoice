import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { resolve } from "node:path";

const [minimumArg, ...inputs] = process.argv.slice(2);
const minimum = Number(minimumArg);
if (!Number.isFinite(minimum) || minimum < 0 || minimum > 100 || inputs.length === 0) {
  console.error("usage: node verify-jacoco-coverage.mjs <minimum-line-percent> <report-or-directory> [...]");
  process.exit(2);
}

const reportNames = new Set(["jacocoTestReport.xml", "jacocoDebugUnitTestReport.xml"]);
const reports = [];

function collect(path) {
  const absolute = resolve(path);
  if (!existsSync(absolute)) return;
  if (!statSync(absolute).isDirectory()) {
    if (absolute.endsWith(".xml")) reports.push(absolute);
    return;
  }
  for (const entry of readdirSync(absolute, { withFileTypes: true })) {
    if (entry.name === "node_modules" || entry.name === ".git") continue;
    const child = resolve(absolute, entry.name);
    if (entry.isDirectory()) collect(child);
    else if (reportNames.has(entry.name)) reports.push(child);
  }
}

inputs.forEach(collect);
const uniqueReports = [...new Set(reports)].sort();
if (uniqueReports.length === 0) {
  console.error(`No JaCoCo XML reports found in: ${inputs.join(", ")}`);
  process.exit(2);
}

let covered = 0;
let missed = 0;
for (const report of uniqueReports) {
  const xml = readFileSync(report, "utf8");
  const counters = [...xml.matchAll(/<counter\s+type="LINE"[^>]*\/>/g)];
  const rootCounter = counters.at(-1)?.[0] ?? "";
  const reportCovered = Number(rootCounter.match(/covered="(\d+)"/)?.[1]);
  const reportMissed = Number(rootCounter.match(/missed="(\d+)"/)?.[1]);
  if (!Number.isFinite(reportCovered) || !Number.isFinite(reportMissed)) {
    console.error(`Missing root LINE counter: ${report}`);
    process.exit(2);
  }
  covered += reportCovered;
  missed += reportMissed;
}

const total = covered + missed;
const percent = total === 0 ? 100 : (covered * 100) / total;
console.log(
  `JaCoCo line coverage: ${covered}/${total} (${percent.toFixed(2)}%), minimum ${minimum.toFixed(2)}% ` +
    `across ${uniqueReports.length} report(s)`,
);
if (percent + Number.EPSILON < minimum) process.exit(1);
