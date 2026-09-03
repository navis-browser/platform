/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import { EN_US_MESSAGES } from "./locales/en-US.mjs";
import { ZH_CN_MESSAGES } from "./locales/zh-CN.mjs";

export const NAVIS_LOCALES = Object.freeze(["en-US", "zh-CN"]);

export const NAVIS_CATALOGS = Object.freeze({
  "en-US": EN_US_MESSAGES,
  "zh-CN": ZH_CN_MESSAGES,
});

const PLACEHOLDER_PATTERN = /\{([A-Za-z][A-Za-z0-9]*)\}/g;

function messagePattern(record, locale, variables) {
  if (typeof record === "string") {
    return record;
  }
  if (!record || typeof record !== "object") {
    throw new TypeError("Invalid Navis localization message record");
  }
  const count = Number(variables.count);
  if (!Number.isFinite(count)) {
    throw new TypeError("Plural Navis message requires a finite count");
  }
  const category = new Intl.PluralRules(locale).select(count);
  return record[category] ?? record.other;
}

function interpolate(pattern, variables, id, numberFormat) {
  const used = new Set();
  const value = pattern.replaceAll(PLACEHOLDER_PATTERN, (_match, name) => {
    if (!Object.hasOwn(variables, name)) {
      throw new TypeError(`Navis message ${id} is missing {${name}}`);
    }
    used.add(name);
    return name === "count" && Number.isFinite(Number(variables[name]))
      ? numberFormat.format(Number(variables[name]))
      : String(variables[name]);
  });
  for (const name of Object.keys(variables)) {
    if (!used.has(name) && name !== "count") {
      throw new TypeError(`Navis message ${id} has unexpected {${name}}`);
    }
  }
  return value;
}

export function normalizeNavisLocale(locale) {
  return NAVIS_LOCALES.includes(locale) ? locale : "en-US";
}

export function getNavisMessageRecord(locale, id) {
  const normalized = normalizeNavisLocale(locale);
  return NAVIS_CATALOGS[normalized][id] ?? EN_US_MESSAGES[id] ?? null;
}

export function createNavisLocalizer(locale) {
  const normalized = normalizeNavisLocale(locale);
  const countFormat = new Intl.NumberFormat(normalized);
  const localizer = {
    locale: normalized,
    text(id, variables = Object.freeze({})) {
      const record = getNavisMessageRecord(normalized, id);
      if (record === null) {
        throw new RangeError(`Unknown Navis localization message: ${id}`);
      }
      return interpolate(
        messagePattern(record, normalized, variables),
        variables,
        id,
        countFormat
      );
    },
    number(value, options = undefined) {
      return new Intl.NumberFormat(normalized, options).format(value);
    },
    dateTime(value, options = undefined) {
      return new Intl.DateTimeFormat(normalized, options).format(
        new Date(value)
      );
    },
  };
  return Object.freeze(localizer);
}

export function navisMessagePlaceholders(record) {
  const patterns =
    typeof record === "string" ? [record] : Object.values(record ?? {});
  const placeholders = new Set();
  for (const pattern of patterns) {
    for (const match of String(pattern).matchAll(PLACEHOLDER_PATTERN)) {
      placeholders.add(match[1]);
    }
  }
  return Object.freeze([...placeholders].sort());
}
