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

for (const name of fs.readdirSync(path.join(root, "fixtures")).filter((n) => n.startsWith("gateway-") && n.endsWith(".json"))) {
  validate("contracts/gateway-messages.schema.json", `fixtures/${name}`);
}

validate("contracts/config.schema.json", "../AutoVoice/app/src/main/assets/demo-full.json");
validate("contracts/config.schema.json", "../AutoVoice/app/src/main/assets/demo-offline.json");

const actionFixture = readJson("fixtures/gateway-reply-action.json");
validate("contracts/intent.schema.json", "fixtures/gateway-reply-action.json#payload.intent", actionFixture.payload.intent);

console.log("Schema validation passed for gateway fixtures, demo configs, and canonical intent.");
