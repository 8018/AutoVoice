import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const root = path.dirname(fileURLToPath(import.meta.url));
const readJson = (file) => JSON.parse(fs.readFileSync(path.join(root, file), "utf8"));
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
ajv.addSchema(readJson("contracts/intent.schema.json"));
const validators = new Map();

function validate(schemaFile, dataFile, data = readJson(dataFile)) {
  let check = validators.get(schemaFile);
  if (!check) {
    const schema = readJson(schemaFile);
    check = ajv.getSchema(schema.$id) ?? ajv.compile(schema);
    validators.set(schemaFile, check);
  }
  if (!check(data)) {
    throw new Error(`${dataFile} does not match ${schemaFile}:\n${ajv.errorsText(check.errors, { separator: "\n" })}`);
  }
}

function reject(schemaFile, dataFile) {
  let check = validators.get(schemaFile);
  if (!check) {
    const schema = readJson(schemaFile);
    check = ajv.getSchema(schema.$id) ?? ajv.compile(schema);
    validators.set(schemaFile, check);
  }
  if (check(readJson(dataFile))) {
    throw new Error(`${dataFile} unexpectedly matches ${schemaFile}`);
  }
}

const checkDraftStops = ajv.compile({ $ref: "https://autovoice/shared/intent.schema.json#/$defs/draftStops" });
for (const name of fs.readdirSync(path.join(root, "fixtures")).filter((n) => n.startsWith("gateway-") && n.endsWith(".json"))) {
  const filename = `fixtures/${name}`;
  const message = readJson(filename);
  validate("contracts/gateway-messages.schema.json", filename, message);
  const intent = message.payload.intent;
  if (intent?.domain === "navigation" && intent.intent === "draft_trip") {
    const stops = JSON.parse(intent.slots.stops.value);
    if (!checkDraftStops(stops)) {
      throw new Error(`${filename} draft stops do not match schema:\n${ajv.errorsText(checkDraftStops.errors)}`);
    }
  }
}

for (const name of fs.readdirSync(path.join(root, "fixtures/invalid")).filter((n) => n.endsWith(".json"))) {
  reject("contracts/gateway-messages.schema.json", `fixtures/invalid/${name}`);
}

for (const channel of ["iflytek", "open"]) {
  for (const asset of ["demo-dev.json", "demo-full.json", "demo-offline.json"]) {
    validate("contracts/config.schema.json", `../AutoVoice/app/src/${channel}/assets/${asset}`);
  }
}

for (const name of fs.readdirSync(path.join(root, "fixtures/config"))) {
  validate("contracts/config.schema.json", `fixtures/config/${name}`);
}
for (const name of fs.readdirSync(path.join(root, "fixtures/invalid-config"))) {
  reject("contracts/config.schema.json", `fixtures/invalid-config/${name}`);
}

const actionFixture = readJson("fixtures/gateway-reply-action.json");
validate("contracts/intent.schema.json", "fixtures/gateway-reply-action.json#payload.intent", actionFixture.payload.intent);

const actionWithoutSource = readJson("fixtures/gateway-reply-action-no-source.json");
validate("contracts/intent.schema.json", "fixtures/gateway-reply-action-no-source.json#payload.intent",
  actionWithoutSource.payload.intent);

console.log("Schema validation passed for valid/invalid gateway fixtures, demo configs, and canonical intent.");
