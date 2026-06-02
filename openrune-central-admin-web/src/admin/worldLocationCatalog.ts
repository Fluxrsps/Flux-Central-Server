/** World-list location ids supported by the client region enum. */
export const WORLD_LOCATION_OPTIONS: { value: string; label: string }[] = [
  { value: "0", label: "United States of America" },
  { value: "1", label: "United Kingdom" },
  { value: "3", label: "Australia" },
  { value: "7", label: "Germany" },
  { value: "8", label: "Brazil" },
];

const VALID_LOCATION_VALUES = new Set(WORLD_LOCATION_OPTIONS.map((o) => o.value));

export function worldLocationLabel(location: unknown): string {
  const s = String(location ?? "");
  const hit = WORLD_LOCATION_OPTIONS.find((o) => o.value === s);
  return hit ? hit.label : `Unknown (${s})`;
}

export function normalizeWorldLocation(raw: unknown): string {
  const s = String(Number(raw));
  return VALID_LOCATION_VALUES.has(s) ? s : "0";
}
