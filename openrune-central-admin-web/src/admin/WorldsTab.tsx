import { useCallback, useEffect, useMemo, useState } from "react";
import type { BridgeDb } from "../bridge/BridgeDb";
import {
  REALMS_LIST,
  WORLDS_ADMIN_LIST,
  WORLD_BROADCAST_INSERT,
  WORLD_CHARACTER_COUNT,
  WORLD_DELETE,
  WORLD_DELETE_PREP_CHARACTERS,
  WORLD_DELETE_PREP_SESSIONS,
  WORLD_EXISTS,
  WORLD_REBOOT_CANCEL,
  WORLD_REBOOT_INSERT,
  WORLD_REBOOT_LIST_ACTIVE,
  WORLD_RENAME_ACTIVITY_LOGS,
  WORLD_RENAME_BROADCAST_LOG,
  WORLD_RENAME_CHARACTERS_ONLINE,
  WORLD_RENAME_CHARACTERS_WORLD,
  WORLD_RENAME_COPY,
  WORLD_RENAME_ONLINE_SAMPLES,
  WORLD_RENAME_REBOOT_SCHEDULES,
  WORLD_RENAME_SESSIONS,
  WORLD_RENAME_WHITELIST,
  WORLD_SESSION_COUNT,
  WORLD_WHITELIST_DELETE,
  WORLD_WHITELIST_INSERT,
  WORLD_WHITELIST_LIST,
} from "./queries";
import { flagsSetToOrderedCsv, parseFlagsCsvToSet, WORLD_FLAG_CATALOG } from "./worldFlagsCatalog";
import { normalizeWorldLocation, WORLD_LOCATION_OPTIONS, worldLocationLabel } from "./worldLocationCatalog";
import { generateWorldKeyMaterial } from "./worldKey";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";

type Row = Record<string, unknown>;

const emptyWorld = () => ({
  world_id: "",
  flags: "MEMBERS",
  host: "127.0.0.1",
  activity: "OpenRune",
  location: "0",
  sort_order: "0",
  enabled: "1",
  max_players: "",
  realm_id: "1",
  login_restrictions_enabled: "0",
  login_gate_min_level_enabled: "0",
  login_gate_rights_enabled: "0",
  login_gate_whitelist_enabled: "0",
  login_min_total_level: "0",
  login_min_rights_token: "modlevel.admin",
  login_rights_select: "modlevel.admin",
  whitelist_usernames: "",
});

const MODLEVEL_RIGHTS_OPTIONS: { value: string; label: string }[] = [
  { value: "modlevel.player", label: "Player mod" },
  { value: "modlevel.moderator", label: "Moderator" },
  { value: "modlevel.admin", label: "Administrator" },
];

function normalizeModLevelRightsToken(raw: unknown): string {
  const t = String(raw ?? "").trim();
  return MODLEVEL_RIGHTS_OPTIONS.some((o) => o.value === t) ? t : "modlevel.admin";
}

async function renameWorldId(db: BridgeDb, oldId: number, newId: number) {
  if (oldId === newId) {
    return;
  }
  const exists = await db.query(WORLD_EXISTS, [newId]);
  if (exists.rows.length > 0) {
    throw new Error(`world_id ${newId} already exists.`);
  }
  await db.query(WORLD_RENAME_COPY, [newId, oldId]);
  await db.query(WORLD_RENAME_WHITELIST, [newId, oldId]);
  await db.query(WORLD_RENAME_SESSIONS, [newId, oldId]);
  await db.query(WORLD_RENAME_CHARACTERS_WORLD, [newId, oldId]);
  await db.query(WORLD_RENAME_CHARACTERS_ONLINE, [newId, oldId]);
  await db.query(WORLD_RENAME_ONLINE_SAMPLES, [newId, oldId]);
  await db.query(WORLD_RENAME_REBOOT_SCHEDULES, [newId, oldId]);
  await db.query(WORLD_RENAME_BROADCAST_LOG, [newId, oldId]);
  await db.query(WORLD_RENAME_ACTIVITY_LOGS, [newId, oldId]);
  await db.query(WORLD_DELETE, [oldId]);
}

export function WorldsTab({ db }: { db: BridgeDb }) {
  const [worldsRows, setWorldRows] = useState<Row[]>([]);
  const [realmsOptions, setRealmOptions] = useState<{ id: number; name: string }[]>([]);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingWorldId, setEditingWorldId] = useState<number | null>(null);
  const [initialRealmId, setInitialRealmId] = useState<number | null>(null);
  const [form, setForm] = useState(emptyWorld);
  const [clearKey, setClearKey] = useState(false);
  const [pendingKeyHex, setPendingKeyHex] = useState<string | null>(null);
  const [lastGeneratedPreview, setLastGeneratedPreview] = useState<string | null>(null);
  const [worldDialogTab, setWorldDialogTab] = useState<"general" | "access">("general");

  const [rebootOpen, setRebootOpen] = useState(false);
  const [rebootAllWorlds, setRebootAllWorlds] = useState(true);
  const [rebootSelectedWorldIds, setRebootSelectedWorldIds] = useState<Set<number>>(new Set());
  const [rebootDelayHrs, setRebootDelayHrs] = useState("0");
  const [rebootDelayMins, setRebootDelayMins] = useState("15");
  const [rebootDelaySecs, setRebootDelaySecs] = useState("0");
  const [rebootMessage, setRebootMessage] = useState("");
  const [rebootBusy, setRebootBusy] = useState(false);
  const [rebootErr, setRebootErr] = useState<string | null>(null);
  const [rebootInfo, setRebootInfo] = useState<string | null>(null);
  const [activeReboots, setActiveReboots] = useState<Row[]>([]);

  const [broadcastOpen, setBroadcastOpen] = useState(false);
  const [bcAllWorlds, setBcAllWorlds] = useState(true);
  const [bcSelectedWorldIds, setBcSelectedWorldIds] = useState<Set<number>>(new Set());
  const [bcMessage, setBcMessage] = useState("");
  const [bcUrl, setBcUrl] = useState("");
  const [bcIcon, setBcIcon] = useState("");
  const [bcBusy, setBcBusy] = useState(false);
  const [bcErr, setBcErr] = useState<string | null>(null);
  const [bcInfo, setBcInfo] = useState<string | null>(null);

  const selectedFlags = useMemo(() => parseFlagsCsvToSet(form.flags), [form.flags]);

  const load = useCallback(async () => {
    setErr(null);
    const [w, r] = await Promise.all([db.query(WORLDS_ADMIN_LIST), db.query(REALMS_LIST)]);
    setWorldRows(w.rows);
    setRealmOptions(
      r.rows.map((row) => ({
        id: Number(row.realm_id),
        name: String(row.name ?? ""),
      })),
    );
  }, [db]);

  useEffect(() => {
    load().catch((e) => setErr(e instanceof Error ? e.message : String(e)));
  }, [load]);

  useEffect(() => {
    if (!dialogOpen || editingWorldId == null) {
      return;
    }
    let cancelled = false;
    db.query(WORLD_WHITELIST_LIST, [editingWorldId])
      .then((r) => {
        if (cancelled) {
          return;
        }
        setForm((f) => ({
          ...f,
          whitelist_usernames: r.rows.map((row) => String(row.account_name ?? "")).join("\n"),
        }));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [dialogOpen, editingWorldId, db]);

  const refreshActiveReboots = useCallback(async () => {
    try {
      const r = await db.query(WORLD_REBOOT_LIST_ACTIVE);
      setActiveReboots(r.rows as Row[]);
    } catch {
      setActiveReboots([]);
    }
  }, [db]);

  const openRebootModal = () => {
    setRebootErr(null);
    setRebootInfo(null);
    setRebootAllWorlds(true);
    setRebootSelectedWorldIds(new Set());
    setRebootDelayHrs("0");
    setRebootDelayMins("15");
    setRebootDelaySecs("0");
    setRebootMessage("Scheduled maintenance reboot.");
    setRebootOpen(true);
    refreshActiveReboots().catch(() => undefined);
  };

  const toggleRebootWorld = (wid: number) => {
    setRebootSelectedWorldIds((prev) => {
      const n = new Set(prev);
      if (n.has(wid)) {
        n.delete(wid);
      } else {
        n.add(wid);
      }
      return n;
    });
  };

  const submitRebootSchedule = async () => {
    setRebootErr(null);
    setRebootInfo(null);
    const parseDelayPart = (s: string) => {
      const n = Number(String(s).trim());
      if (!Number.isFinite(n) || n < 0) {
        return NaN;
      }
      return Math.floor(n);
    };
    const h = parseDelayPart(rebootDelayHrs);
    const m = parseDelayPart(rebootDelayMins);
    const sec = parseDelayPart(rebootDelaySecs);
    if ([h, m, sec].some((x) => Number.isNaN(x))) {
      setRebootErr("Hours, minutes, and seconds must be non-negative numbers.");
      return;
    }
    const totalSec = h * 3600 + m * 60 + sec;
    if (totalSec < 1) {
      setRebootErr("Set a delay of at least 1 second.");
      return;
    }
    const iso = new Date(Date.now() + totalSec * 1000).toISOString();
    if (!rebootAllWorlds && rebootSelectedWorldIds.size === 0) {
      setRebootErr("Select at least one world, or choose All worlds.");
      return;
    }
    setRebootBusy(true);
    try {
      const by = "admin-web";
      if (rebootAllWorlds) {
        await db.query(WORLD_REBOOT_INSERT, [null, iso, rebootMessage.trim() || "Scheduled reboot.", by]);
        setRebootInfo("Scheduled reboot for all worlds.");
      } else {
        for (const wid of rebootSelectedWorldIds) {
          await db.query(WORLD_REBOOT_INSERT, [wid, iso, rebootMessage.trim() || "Scheduled reboot.", by]);
        }
        setRebootInfo(`Scheduled reboot for ${rebootSelectedWorldIds.size} world(s).`);
      }
      await refreshActiveReboots();
    } catch (e) {
      setRebootErr(e instanceof Error ? e.message : String(e));
    } finally {
      setRebootBusy(false);
    }
  };

  const cancelRebootRow = async (id: number) => {
    setRebootBusy(true);
    setRebootErr(null);
    try {
      await db.query(WORLD_REBOOT_CANCEL, [id]);
      setRebootInfo(`Cancelled schedule ${id}.`);
      await refreshActiveReboots();
    } catch (e) {
      setRebootErr(e instanceof Error ? e.message : String(e));
    } finally {
      setRebootBusy(false);
    }
  };

  const openBroadcastModal = () => {
    setBcErr(null);
    setBcInfo(null);
    setBcAllWorlds(true);
    setBcSelectedWorldIds(new Set());
    setBcMessage("");
    setBcUrl("");
    setBcIcon("");
    setBroadcastOpen(true);
  };

  const toggleBcWorld = (wid: number) => {
    setBcSelectedWorldIds((prev) => {
      const n = new Set(prev);
      if (n.has(wid)) {
        n.delete(wid);
      } else {
        n.add(wid);
      }
      return n;
    });
  };

  const submitBroadcast = async () => {
    setBcErr(null);
    setBcInfo(null);
    if (!bcMessage.trim()) {
      setBcErr("Message is required.");
      return;
    }
    if (!bcAllWorlds && bcSelectedWorldIds.size === 0) {
      setBcErr("Select at least one world, or choose All worlds.");
      return;
    }
    setBcBusy(true);
    try {
      const by = "admin-web";
      const msg = bcMessage.trim();
      const url = bcUrl.trim();
      const icon = bcIcon.trim();
      if (bcAllWorlds) {
        await db.query(WORLD_BROADCAST_INSERT, [null, msg, url, icon, by]);
        setBcInfo("Broadcast queued for all worlds.");
      } else {
        for (const wid of bcSelectedWorldIds) {
          await db.query(WORLD_BROADCAST_INSERT, [wid, msg, url, icon, by]);
        }
        setBcInfo(`Broadcast queued for ${bcSelectedWorldIds.size} world(s).`);
      }
    } catch (e) {
      setBcErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBcBusy(false);
    }
  };

  const realmsName = useMemo(() => {
    const m = new Map(realmsOptions.map((o) => [o.id, o.name]));
    return (id: number) => m.get(id) ?? String(id);
  }, [realmsOptions]);

  const toggleFlag = (name: string) => {
    const s = parseFlagsCsvToSet(form.flags);
    if (s.has(name)) {
      s.delete(name);
    } else {
      s.add(name);
    }
    setForm({ ...form, flags: flagsSetToOrderedCsv(s) });
  };

  const openCreate = () => {
    setEditingWorldId(null);
    setInitialRealmId(null);
    setForm(emptyWorld());
    setWorldDialogTab("general");
    setClearKey(false);
    setPendingKeyHex(null);
    setLastGeneratedPreview(null);
    setInfo(null);
    setErr(null);
    setDialogOpen(true);
  };

  const openEdit = (row: Row) => {
    const wid = Number(row.world_id);
    setEditingWorldId(wid);
    const rid = Number(row.realm_id);
    setInitialRealmId(rid);
    setForm({
      world_id: String(wid),
      flags: String(row.flags ?? ""),
      host: String(row.host ?? ""),
      activity: String(row.activity ?? ""),
      location: normalizeWorldLocation(row.location),
      sort_order: String(row.sort_order ?? "0"),
      enabled: Number(row.enabled) ? "1" : "0",
      max_players: row.max_players == null ? "" : String(row.max_players),
      realm_id: String(rid),
      login_restrictions_enabled: Number(row.login_restrictions_enabled) ? "1" : "0",
      login_gate_min_level_enabled: Number(row.login_gate_min_level_enabled ?? 0) ? "1" : "0",
      login_gate_rights_enabled: Number(row.login_gate_rights_enabled ?? 0) ? "1" : "0",
      login_gate_whitelist_enabled: Number(row.login_gate_whitelist_enabled ?? 0) ? "1" : "0",
      login_min_total_level: String(row.login_min_total_level ?? "0"),
      ...(() => {
        const m = normalizeModLevelRightsToken(row.login_min_rights_token);
        return { login_min_rights_token: m, login_rights_select: m };
      })(),
      whitelist_usernames: "",
    });
    setWorldDialogTab("general");
    setClearKey(false);
    setPendingKeyHex(null);
    setLastGeneratedPreview(null);
    setInfo(null);
    setErr(null);
    setDialogOpen(true);
  };

  const genKey = async () => {
    setErr(null);
    try {
      const { worldsKey, sha256Hex } = await generateWorldKeyMaterial();
      setPendingKeyHex(sha256Hex);
      setClearKey(false);
      setLastGeneratedPreview(worldsKey);
      setInfo("New key staged — save to apply.");
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };

  const save = async () => {
    setBusy(true);
    setErr(null);
    setInfo(null);
    try {
      const flags = form.flags.trim();
      const host = form.host.trim();
      const activity = form.activity.trim();
      const location = Number(form.location);
      if (!WORLD_LOCATION_OPTIONS.some((o) => o.value === String(location))) {
        setErr("location must be a supported region (0, 1, 3, 7, or 8).");
        setBusy(false);
        return;
      }
      const sort_order = Number(form.sort_order) || 0;
      const enabled = Number(form.enabled) ? 1 : 0;
      let max_players: number | null = null;
      if (form.max_players.trim() !== "") {
        const mp = Number(form.max_players);
        if (!Number.isFinite(mp)) {
          setErr("max_players must be a number or empty.");
          setBusy(false);
          return;
        }
        max_players = mp;
      }
      const realm_id = Number(form.realm_id);
      if (!realm_id) {
        setErr("realm_id required.");
        setBusy(false);
        return;
      }

      const gateMin = form.login_gate_min_level_enabled === "1";
      const gateRights = form.login_gate_rights_enabled === "1";
      const gateWhitelist = form.login_gate_whitelist_enabled === "1";
      const login_restrictions_enabled = gateMin || gateRights || gateWhitelist ? 1 : 0;

      let login_min_total_level = 0;
      if (form.login_min_total_level.trim() !== "") {
        login_min_total_level = Math.floor(Number(form.login_min_total_level));
        if (!Number.isFinite(login_min_total_level) || login_min_total_level < 0) {
          setErr("Min total level must be a non-negative number.");
          setBusy(false);
          return;
        }
      }
      if (gateMin && login_min_total_level < 1) {
        setErr("When the Min level badge is on, set a minimum total level of at least 1.");
        setBusy(false);
        return;
      }

      let login_min_rights_token: string | null = null;
      if (gateRights) {
        login_min_rights_token = normalizeModLevelRightsToken(form.login_rights_select);
      }

      const login_gate_min_level_enabled = gateMin ? 1 : 0;
      const login_gate_rights_enabled = gateRights ? 1 : 0;
      const login_gate_whitelist_enabled = gateWhitelist ? 1 : 0;

      const persistWhitelist = async (wid: number) => {
        await db.query(WORLD_WHITELIST_DELETE, [wid]);
        for (const line of form.whitelist_usernames.split(/\r?\n/)) {
          const u = line.trim();
          if (u) {
            await db.query(WORLD_WHITELIST_INSERT, [wid, u]);
          }
        }
      };

      if (editingWorldId == null) {
        const wid = Number(form.world_id);
        if (!Number.isFinite(wid)) {
          setErr("world_id must be a number.");
          setBusy(false);
          return;
        }
        const ins = `
INSERT INTO worlds (
  world_id, flags, host, activity, location, population, sort_order, enabled, max_players, world_key_sha256, realm_id,
  login_restrictions_enabled, login_min_total_level, login_min_rights_token,
  login_gate_min_level_enabled, login_gate_rights_enabled, login_gate_whitelist_enabled
)
VALUES ($1, $2, $3, $4, $5, 0, $6, $7, $8, NULL, $9, $10, $11, $12, $13, $14, $15)
`.trim();
        await db.query(ins, [
          wid,
          flags,
          host,
          activity,
          location,
          sort_order,
          enabled,
          max_players,
          realm_id,
          login_restrictions_enabled,
          login_min_total_level,
          login_min_rights_token,
          login_gate_min_level_enabled,
          login_gate_rights_enabled,
          login_gate_whitelist_enabled,
        ]);
        if (pendingKeyHex) {
          await db.query(`UPDATE worlds SET world_key_sha256 = decode($1, 'hex') WHERE world_id = $2`, [pendingKeyHex, wid]);
        }
        await persistWhitelist(wid);
        setInfo(`Created worlds ${wid}.`);
        setEditingWorldId(wid);
        setInitialRealmId(realm_id);
        setPendingKeyHex(null);
      } else {
        let wid = editingWorldId;
        const newWid = Number(form.world_id);
        if (!Number.isFinite(newWid)) {
          setErr("world_id must be a number.");
          setBusy(false);
          return;
        }
        if (newWid !== wid) {
          await renameWorldId(db, wid, newWid);
          wid = newWid;
          setEditingWorldId(newWid);
        }
        await db.query(
          `UPDATE worlds SET flags = $1, host = $2, activity = $3, location = $4, sort_order = $5, enabled = $6, max_players = $7,
realm_id = $8,
login_restrictions_enabled = $9, login_min_total_level = $10, login_min_rights_token = $11,
login_gate_min_level_enabled = $12, login_gate_rights_enabled = $13, login_gate_whitelist_enabled = $14
WHERE world_id = $15`,
          [
            flags,
            host,
            activity,
            location,
            sort_order,
            enabled,
            max_players,
            realm_id,
            login_restrictions_enabled,
            login_min_total_level,
            login_min_rights_token,
            login_gate_min_level_enabled,
            login_gate_rights_enabled,
            login_gate_whitelist_enabled,
            wid,
          ],
        );
        if (clearKey) {
          await db.query(`UPDATE worlds SET world_key_sha256 = NULL WHERE world_id = $1`, [wid]);
        } else if (pendingKeyHex) {
          await db.query(`UPDATE worlds SET world_key_sha256 = decode($1, 'hex') WHERE world_id = $2`, [pendingKeyHex, wid]);
        }
        setInitialRealmId(realm_id);
        await persistWhitelist(wid);
        setInfo(`Updated worlds ${wid}.`);
        setPendingKeyHex(null);
        setClearKey(false);
      }
      await load();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (editingWorldId == null) {
      return;
    }
    const wid = editingWorldId;
    setBusy(true);
    setErr(null);
    setInfo(null);
    try {
      const sess = await db.query(WORLD_SESSION_COUNT, [wid]);
      const sessCount = Number(sess.rows[0]?.c ?? 0);
      const chars = await db.query(WORLD_CHARACTER_COUNT, [wid]);
      const charCount = Number(chars.rows[0]?.c ?? 0);
      if (sessCount > 0) {
        await db.query(WORLD_DELETE_PREP_SESSIONS, [wid]);
      }
      if (charCount > 0) {
        await db.query(WORLD_DELETE_PREP_CHARACTERS, [wid]);
      }
      const del = await db.query(WORLD_DELETE, [wid]);
      if ((del.rowCount ?? 0) < 1) {
        setErr("Delete affected 0 rows (world missing).");
      } else {
        const bits: string[] = [`Deleted world ${wid}.`];
        if (sessCount > 0) {
          bits.push(`ended ${sessCount} session(s).`);
        }
        if (charCount > 0) {
          bits.push(`cleared ${charCount} character world link(s).`);
        }
        setInfo(bits.join(" "));
        setDialogOpen(false);
        setEditingWorldId(null);
        await load();
      }
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="secondary" size="sm" onClick={() => load().catch((e) => setErr(String(e)))}>
          Refresh
        </Button>
        <Button type="button" size="sm" onClick={openCreate}>
          Add worlds
        </Button>
        <Button type="button" variant="outline" size="sm" onClick={openRebootModal}>
          Reboot
        </Button>
        <Button type="button" variant="outline" size="sm" onClick={openBroadcastModal}>
          Broadcast
        </Button>
      </div>
      {err && !dialogOpen && <p className="text-sm text-destructive">{err}</p>}
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-14">id</TableHead>
            <TableHead>host</TableHead>
            <TableHead className="w-28">location</TableHead>
            <TableHead>realms</TableHead>
            <TableHead className="w-12">en</TableHead>
            <TableHead className="w-14">key</TableHead>
            <TableHead className="w-16">online</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {worldsRows.map((row) => (
            <TableRow key={String(row.world_id)} className="cursor-pointer" onClick={() => openEdit(row)}>
              <TableCell className="font-mono text-xs">{String(row.world_id)}</TableCell>
              <TableCell className="text-sm">{String(row.host)}</TableCell>
              <TableCell className="text-muted-foreground text-xs">{worldLocationLabel(row.location)}</TableCell>
              <TableCell className="text-muted-foreground text-xs">{realmsName(Number(row.realm_id))}</TableCell>
              <TableCell>{Number(row.enabled) ? "online" : "offline"}</TableCell>
              <TableCell>{Number(row.has_key) ? "yes" : "no"}</TableCell>
              <TableCell>{String(row.online_count ?? 0)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      <Dialog open={rebootOpen} onOpenChange={setRebootOpen}>
        <DialogContent className="max-h-[90vh] max-w-lg overflow-y-auto">
          <DialogHeader>
            <DialogTitle>Schedule reboot</DialogTitle>
            <DialogDescription>
              Writes to <code className="text-xs">world_reboot_schedules</code> and NOTIFYs linked game servers.
              New Central logins are blocked shortly before reboot (based on notice length).
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3 py-2">
            {rebootErr && <p className="text-sm text-destructive">{rebootErr}</p>}
            {rebootInfo && <p className="text-sm text-green-600 dark:text-green-400">{rebootInfo}</p>}
            <div className="flex items-center gap-2">
              <Checkbox
                id="rb-all"
                checked={rebootAllWorlds}
                onCheckedChange={(c) => {
                  const v = c === true;
                  setRebootAllWorlds(v);
                  if (v) {
                    setRebootSelectedWorldIds(new Set());
                  }
                }}
              />
              <Label htmlFor="rb-all" className="cursor-pointer font-normal">
                All worlds
              </Label>
            </div>
            {!rebootAllWorlds && (
              <div className="max-h-40 space-y-1 overflow-y-auto rounded border p-2">
                {worldsRows.map((row) => {
                  const wid = Number(row.world_id);
                  return (
                    <label key={wid} className="flex cursor-pointer items-center gap-2 text-sm">
                      <Checkbox
                        checked={rebootSelectedWorldIds.has(wid)}
                        onCheckedChange={() => toggleRebootWorld(wid)}
                      />
                      <span className="font-mono text-xs">{wid}</span>
                      <span className="truncate text-muted-foreground">{String(row.host)}</span>
                    </label>
                  );
                })}
              </div>
            )}
            <div className="space-y-1">
              <Label>Delay until reboot</Label>
              <p className="text-xs text-muted-foreground">From when you click Schedule (UTC stored in DB).</p>
              <div className="flex flex-wrap items-end gap-2">
                <div className="space-y-1">
                  <Label htmlFor="rb-dh" className="text-xs text-muted-foreground">
                    Hrs
                  </Label>
                  <Input
                    id="rb-dh"
                    className="w-[4.5rem] font-mono"
                    type="number"
                    inputMode="numeric"
                    min={0}
                    step={1}
                    value={rebootDelayHrs}
                    onChange={(e) => setRebootDelayHrs(e.target.value)}
                  />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="rb-dm" className="text-xs text-muted-foreground">
                    Min
                  </Label>
                  <Input
                    id="rb-dm"
                    className="w-[4.5rem] font-mono"
                    type="number"
                    inputMode="numeric"
                    min={0}
                    step={1}
                    value={rebootDelayMins}
                    onChange={(e) => setRebootDelayMins(e.target.value)}
                  />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="rb-ds" className="text-xs text-muted-foreground">
                    Sec
                  </Label>
                  <Input
                    id="rb-ds"
                    className="w-[4.5rem] font-mono"
                    type="number"
                    inputMode="numeric"
                    min={0}
                    step={1}
                    value={rebootDelaySecs}
                    onChange={(e) => setRebootDelaySecs(e.target.value)}
                  />
                </div>
              </div>
            </div>
            <div className="space-y-1">
              <Label htmlFor="rb-msg">Message</Label>
              <Textarea id="rb-msg" rows={3} value={rebootMessage} onChange={(e) => setRebootMessage(e.target.value)} />
            </div>
            <div className="space-y-1">
              <p className="text-xs font-medium text-muted-foreground">Active schedules</p>
              {activeReboots.length === 0 ? (
                <p className="text-xs text-muted-foreground">None</p>
              ) : (
                <ul className="space-y-1 text-xs">
                  {activeReboots.map((r) => (
                    <li key={String(r.id)} className="flex flex-wrap items-center justify-between gap-2 border-b border-border/60 py-1">
                      <span>
                        <span className="font-mono">#{String(r.id)}</span>{" "}
                        {r.world_id == null ? (
                          <span className="text-muted-foreground">all</span>
                        ) : (
                          <span className="font-mono">w{String(r.world_id)}</span>
                        )}{" "}
                        <span className="text-muted-foreground">{String(r.reboot_at)}</span>
                      </span>
                      <Button type="button" variant="outline" size="sm" className="h-7 text-[10px]" disabled={rebootBusy} onClick={() => cancelRebootRow(Number(r.id))}>
                        Cancel
                      </Button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" size="sm" onClick={() => setRebootOpen(false)}>
              Close
            </Button>
            <Button type="button" size="sm" disabled={rebootBusy} onClick={() => submitRebootSchedule().catch((e) => setRebootErr(String(e)))}>
              {rebootBusy ? "Saving…" : "Schedule"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={broadcastOpen} onOpenChange={setBroadcastOpen}>
        <DialogContent className="max-h-[90vh] max-w-lg overflow-y-auto">
          <DialogHeader>
            <DialogTitle>Broadcast</DialogTitle>
            <DialogDescription>
              Logged in <code className="text-xs">world_broadcast_log</code>. Games show the message as broadcast chat (URL/icon reserved for later).
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3 py-2">
            {bcErr && <p className="text-sm text-destructive">{bcErr}</p>}
            {bcInfo && <p className="text-sm text-green-600 dark:text-green-400">{bcInfo}</p>}
            <div className="flex items-center gap-2">
              <Checkbox
                id="bc-all"
                checked={bcAllWorlds}
                onCheckedChange={(c) => {
                  const v = c === true;
                  setBcAllWorlds(v);
                  if (v) {
                    setBcSelectedWorldIds(new Set());
                  }
                }}
              />
              <Label htmlFor="bc-all" className="cursor-pointer font-normal">
                All worlds
              </Label>
            </div>
            {!bcAllWorlds && (
              <div className="max-h-40 space-y-1 overflow-y-auto rounded border p-2">
                {worldsRows.map((row) => {
                  const wid = Number(row.world_id);
                  return (
                    <label key={wid} className="flex cursor-pointer items-center gap-2 text-sm">
                      <Checkbox checked={bcSelectedWorldIds.has(wid)} onCheckedChange={() => toggleBcWorld(wid)} />
                      <span className="font-mono text-xs">{wid}</span>
                      <span className="truncate text-muted-foreground">{String(row.host)}</span>
                    </label>
                  );
                })}
              </div>
            )}
            <div className="space-y-1">
              <Label htmlFor="bc-msg">Message</Label>
              <Textarea id="bc-msg" rows={3} value={bcMessage} onChange={(e) => setBcMessage(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="bc-url">URL (optional)</Label>
              <Input id="bc-url" value={bcUrl} onChange={(e) => setBcUrl(e.target.value)} placeholder="https://…" />
            </div>
            <div className="space-y-1">
              <Label htmlFor="bc-icon">Icon (optional)</Label>
              <Input id="bc-icon" value={bcIcon} onChange={(e) => setBcIcon(e.target.value)} placeholder="sprite id or URL" />
            </div>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" size="sm" onClick={() => setBroadcastOpen(false)}>
              Close
            </Button>
            <Button type="button" size="sm" disabled={bcBusy} onClick={() => submitBroadcast().catch((e) => setBcErr(String(e)))}>
              {bcBusy ? "Sending…" : "Send"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={dialogOpen}
        onOpenChange={(o) => {
          setDialogOpen(o);
          if (!o) {
            setErr(null);
            setInfo(null);
          }
        }}
      >
        <DialogContent className="flex max-h-[92vh] w-[min(96vw,56rem)] max-w-none flex-col gap-0 overflow-hidden p-0 sm:max-w-none">
          <DialogHeader className="space-y-0 border-b px-6 py-4 text-left">
            <DialogTitle>
              {editingWorldId == null ? "New world" : `Edit world ${form.world_id || editingWorldId}`}
            </DialogTitle>
            <DialogDescription className="sr-only">World form</DialogDescription>
          </DialogHeader>
          <ScrollArea className="max-h-[min(78vh,760px)] px-6">
            <div className="space-y-4 py-4 pr-3">
              {err && <p className="text-sm text-destructive">{err}</p>}
              {info && <p className="text-sm text-green-400">{info}</p>}
              <Tabs value={worldDialogTab} onValueChange={(v) => setWorldDialogTab(v as "general" | "access")}>
                <TabsList className="w-full justify-start">
                  <TabsTrigger value="general">General</TabsTrigger>
                  <TabsTrigger value="access">Login access</TabsTrigger>
                </TabsList>
                <TabsContent value="general" className="mt-4 space-y-4 focus-visible:outline-none">
                  <div className="space-y-2">
                    <Label htmlFor="w-id">world_id</Label>
                    <Input
                      id="w-id"
                      className="max-w-xs font-mono"
                      inputMode="numeric"
                      value={form.world_id}
                      onChange={(e) => setForm({ ...form, world_id: e.target.value })}
                    />
                    {editingWorldId != null && (
                      <p className="text-xs text-muted-foreground">
                        Changing id copies this world to the new id and updates linked sessions, characters, and logs.
                      </p>
                    )}
                  </div>
                  <div className="space-y-2">
                    <Label>Flags</Label>
                    <div className="flex max-h-[220px] flex-wrap gap-1.5 overflow-y-auto rounded-md border border-border bg-muted/20 p-2">
                      {WORLD_FLAG_CATALOG.map((f) => {
                        const on = selectedFlags.has(f.name);
                        return (
                          <button
                            key={f.name}
                            type="button"
                            className={cn(
                              "rounded-full border px-2 py-0.5 text-[11px] font-medium transition-colors",
                              on
                                ? "border-primary bg-primary text-primary-foreground"
                                : "border-border bg-background text-muted-foreground hover:bg-muted",
                            )}
                            onClick={() => toggleFlag(f.name)}
                          >
                            {f.name}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                  <div className="grid gap-3 sm:grid-cols-2">
                    <div className="space-y-2">
                      <Label htmlFor="w-host">host</Label>
                      <Input id="w-host" value={form.host} onChange={(e) => setForm({ ...form, host: e.target.value })} />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="w-act">activity</Label>
                      <Input id="w-act" value={form.activity} onChange={(e) => setForm({ ...form, activity: e.target.value })} />
                    </div>
                  </div>
                  <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                    <div className="space-y-2">
                      <Label htmlFor="w-loc">location</Label>
                      <Select value={form.location} onValueChange={(v) => setForm({ ...form, location: v })}>
                        <SelectTrigger id="w-loc">
                          <SelectValue placeholder="Location" />
                        </SelectTrigger>
                        <SelectContent>
                          {WORLD_LOCATION_OPTIONS.map((o) => (
                            <SelectItem key={o.value} value={o.value}>
                              {o.value} — {o.label}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="w-sort">sort_order</Label>
                      <Input id="w-sort" value={form.sort_order} onChange={(e) => setForm({ ...form, sort_order: e.target.value })} />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="w-en">worlds status</Label>
                      <button
                        id="w-en"
                        type="button"
                        className={cn(
                          "flex h-9 w-full items-center justify-between rounded-md border px-3 text-sm transition-colors",
                          form.enabled === "1"
                            ? "border-emerald-500 bg-emerald-500/15 text-emerald-300"
                            : "border-red-500 bg-red-500/15 text-red-300",
                        )}
                        onClick={() => setForm({ ...form, enabled: form.enabled === "1" ? "0" : "1" })}
                      >
                        <span>{form.enabled === "1" ? "Online" : "Offline"}</span>
                        <span
                          className={cn(
                            "inline-block h-2.5 w-2.5 rounded-full",
                            form.enabled === "1" ? "bg-emerald-400" : "bg-red-400",
                          )}
                        />
                      </button>
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="w-max">max_players</Label>
                      <Input
                        id="w-max"
                        placeholder="empty = null"
                        value={form.max_players}
                        onChange={(e) => setForm({ ...form, max_players: e.target.value })}
                      />
                    </div>
                  </div>
                  <div className="space-y-2">
                    <Label>realms</Label>
                    <Select value={form.realm_id} onValueChange={(v) => setForm({ ...form, realm_id: v })}>
                      <SelectTrigger>
                        <SelectValue placeholder="Realm" />
                      </SelectTrigger>
                      <SelectContent>
                        {realmsOptions.map((o) => (
                          <SelectItem key={o.id} value={String(o.id)}>
                            {o.id}: {o.name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  {editingWorldId != null && (
                    <div className="flex items-center gap-2">
                      <Checkbox
                        id="w-clear-key"
                        checked={clearKey}
                        onCheckedChange={(c) => {
                          const v = c === true;
                          setClearKey(v);
                          if (v) {
                            setPendingKeyHex(null);
                            setLastGeneratedPreview(null);
                          }
                        }}
                      />
                      <Label htmlFor="w-clear-key" className="cursor-pointer font-normal text-muted-foreground">
                        Clear worlds key on save
                      </Label>
                    </div>
                  )}
                  <div className="flex flex-wrap items-center gap-2">
                    <Button type="button" variant="secondary" size="sm" onClick={genKey}>
                      Generate worlds key
                    </Button>
                    {lastGeneratedPreview && (
                      <Input
                        readOnly
                        value={lastGeneratedPreview}
                        className="h-8 min-w-[360px] max-w-full font-mono text-xs"
                        aria-label="Generated worlds key"
                      />
                    )}
                  </div>
                </TabsContent>
                <TabsContent value="access" className="mt-4 space-y-4 focus-visible:outline-none">
                  <p className="text-sm text-muted-foreground">
                    Turn on a badge to enforce that rule. A login passes if any enabled rule matches.
                  </p>
                  <div className="flex flex-wrap gap-2">
                    <button
                      type="button"
                      className={cn(
                        "rounded-full border px-3 py-1 text-xs font-medium transition-colors",
                        form.login_gate_min_level_enabled === "1"
                          ? "border-sky-500 bg-sky-500/20 text-sky-200"
                          : "border-muted-foreground/30 bg-muted/30 text-muted-foreground hover:bg-muted/50",
                      )}
                      onClick={() =>
                        setForm({
                          ...form,
                          login_gate_min_level_enabled: form.login_gate_min_level_enabled === "1" ? "0" : "1",
                        })
                      }
                    >
                      Min total level
                    </button>
                    <button
                      type="button"
                      className={cn(
                        "rounded-full border px-3 py-1 text-xs font-medium transition-colors",
                        form.login_gate_rights_enabled === "1"
                          ? "border-violet-500 bg-violet-500/20 text-violet-200"
                          : "border-muted-foreground/30 bg-muted/30 text-muted-foreground hover:bg-muted/50",
                      )}
                      onClick={() =>
                        setForm({
                          ...form,
                          login_gate_rights_enabled: form.login_gate_rights_enabled === "1" ? "0" : "1",
                        })
                      }
                    >
                      Rights token
                    </button>
                    <button
                      type="button"
                      className={cn(
                        "rounded-full border px-3 py-1 text-xs font-medium transition-colors",
                        form.login_gate_whitelist_enabled === "1"
                          ? "border-amber-500 bg-amber-500/20 text-amber-200"
                          : "border-muted-foreground/30 bg-muted/30 text-muted-foreground hover:bg-muted/50",
                      )}
                      onClick={() =>
                        setForm({
                          ...form,
                          login_gate_whitelist_enabled: form.login_gate_whitelist_enabled === "1" ? "0" : "1",
                        })
                      }
                    >
                      Whitelist
                    </button>
                  </div>
                  {form.login_gate_min_level_enabled === "1" && (
                    <div className="space-y-3 rounded-md border border-border/60 p-3">
                      <div className="space-y-2">
                        <Label htmlFor="w-min-tl">Minimum total base level</Label>
                        <Input
                          id="w-min-tl"
                          className="max-w-xs font-mono"
                          inputMode="numeric"
                          value={form.login_min_total_level}
                          onChange={(e) => setForm({ ...form, login_min_total_level: e.target.value })}
                          placeholder="e.g. 500"
                        />
                      </div>
                    </div>
                  )}
                  {form.login_gate_rights_enabled === "1" && (
                    <div className="space-y-3 rounded-md border border-border/60 p-3">
                      <div className="space-y-2">
                        <Label>Required mod level</Label>
                        <Select
                          value={form.login_rights_select}
                          onValueChange={(v) =>
                            setForm({ ...form, login_rights_select: v, login_min_rights_token: v })
                          }
                        >
                          <SelectTrigger className="max-w-md">
                            <SelectValue placeholder="Mod level" />
                          </SelectTrigger>
                          <SelectContent>
                            {MODLEVEL_RIGHTS_OPTIONS.map((o) => (
                              <SelectItem key={o.value} value={o.value}>
                                {o.label}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>
                    </div>
                  )}
                  {form.login_gate_whitelist_enabled === "1" && (
                    <div className="space-y-3 rounded-md border border-border/60 p-3">
                      <div className="space-y-2">
                        <Label htmlFor="w-whitelist">Whitelist (Central login usernames, one per line)</Label>
                        <Textarea
                          id="w-whitelist"
                          rows={8}
                          className="font-mono text-xs"
                          value={form.whitelist_usernames}
                          onChange={(e) => setForm({ ...form, whitelist_usernames: e.target.value })}
                          placeholder={"vip_player\nstaff_mule"}
                        />
                      </div>
                    </div>
                  )}
                </TabsContent>
              </Tabs>
            </div>
          </ScrollArea>
          <DialogFooter className="flex-row flex-wrap gap-2 border-t px-6 py-4 sm:justify-between">
            <div className="flex gap-2">
              {editingWorldId != null && (
                <Button type="button" variant="destructive" size="sm" disabled={busy} onClick={remove}>
                  Delete
                </Button>
              )}
            </div>
            <div className="flex gap-2">
              <Button type="button" variant="outline" size="sm" onClick={() => setDialogOpen(false)}>
                Close
              </Button>
              <Button type="button" size="sm" disabled={busy} onClick={save}>
                {busy ? "Saving…" : "Save"}
              </Button>
            </div>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
