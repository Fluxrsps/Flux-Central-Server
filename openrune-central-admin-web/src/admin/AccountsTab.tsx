import { useCallback, useEffect, useMemo, useState } from "react";
import type { BridgeDb } from "../bridge/BridgeDb";
import type { QueryResultPayload } from "../bridgeClient";
import {
  ACCOUNT_BY_ID,
  ACCOUNT_CHARACTERS,
  ACCOUNT_SESSIONS,
  ACCOUNTS_COUNT_ALL,
  ACCOUNTS_COUNT_SEARCH,
  ACCOUNTS_LIST_PAGE,
  ACCOUNTS_SEARCH_PAGE,
  CHARACTER_DISPLAY_NAME_APPLY,
  CHARACTER_DISPLAY_NAME_CONTEXT,
  DISPLAY_NAME_TAKEN_AS_OTHER_LOGIN,
  DISPLAY_NAME_TAKEN_BY_OTHER_CHARACTER,
  escapeLike,
  PUNISHMENT_INSERT,
  PUNISHMENTS_FOR_ACCOUNT,
  PUNISHMENT_UPDATE_STATUS,
} from "./queries";
import { validateStaffDisplayNameFormat } from "./displayNamePolicy";
import { ResultTable } from "./ResultTable";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
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
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";

const PAGE = 25;
const RIGHTS_PRESETS = [
  "modlevel.player",
  "modlevel.moderator",
  "modlevel.admin",
  "modlevel.owner",
] as const;

type AccountRow = Record<string, unknown>;

function fmt(v: unknown): string {
  if (v == null) {
    return "—";
  }
  return String(v);
}

export function AccountsTab({ db }: { db: BridgeDb }) {
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [rows, setRows] = useState<AccountRow[]>([]);
  const [total, setTotal] = useState(0);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<AccountRow | null>(null);
  const [rightsEdit, setRightsEdit] = useState("");
  const [sessionsRes, setSessionsRes] = useState<QueryResultPayload | null>(null);
  const [charsRes, setCharsRes] = useState<QueryResultPayload | null>(null);
  const [charsNote, setCharsNote] = useState<string | null>(null);
  const [punishRes, setPunishRes] = useState<QueryResultPayload | null>(null);
  const [punishNote, setPunishNote] = useState<string | null>(null);
  const [sessionsNote, setSessionsNote] = useState<string | null>(null);
  const [saveInfo, setSaveInfo] = useState<string | null>(null);
  const [accountModalTab, setAccountModalTab] = useState<"overview" | "punishments">("overview");
  const [pScope, setPScope] = useState<"account" | "character">("account");
  const [pKind, setPKind] = useState("ban");
  /** Character row id from ACCOUNT_CHARACTERS, as string for Select */
  const [pSelectedCharacterId, setPSelectedCharacterId] = useState("");
  const [pExpires, setPExpires] = useState("");
  const [pReason, setPReason] = useState("");
  const [pPriv, setPPriv] = useState("");
  const [pPub, setPPub] = useState("");
  const [pIssued, setPIssued] = useState("admin-web");
  const [pApproved, setPApproved] = useState("");
  const [punishBusy, setPunishBusy] = useState(false);
  const [punishInfo, setPunishInfo] = useState<string | null>(null);
  const [renameCharId, setRenameCharId] = useState("");
  const [renameInput, setRenameInput] = useState("");
  const [renameBusy, setRenameBusy] = useState(false);
  const [renameInfo, setRenameInfo] = useState<string | null>(null);
  const rightsOptions = useMemo(() => {
    const s = new Set<string>(RIGHTS_PRESETS);
    const current = rightsEdit.trim();
    if (current.length > 0) {
      s.add(current);
    }
    return [...s];
  }, [rightsEdit]);

  const accountCharacters = useMemo(() => {
    const rows = charsRes?.rows ?? [];
    return rows as AccountRow[];
  }, [charsRes]);

  useEffect(() => {
    if (accountCharacters.length === 0 && pScope === "character") {
      setPScope("account");
    }
  }, [accountCharacters.length, pScope]);

  useEffect(() => {
    if (pScope !== "character" || accountCharacters.length === 0) {
      return;
    }
    const ids = accountCharacters.map((r) => String(r.id));
    if (!pSelectedCharacterId || !ids.includes(pSelectedCharacterId)) {
      setPSelectedCharacterId(ids[0] ?? "");
    }
  }, [pScope, accountCharacters, pSelectedCharacterId]);

  useEffect(() => {
    if (accountCharacters.length === 0) {
      setRenameCharId("");
      return;
    }
    const ids = accountCharacters.map((r) => String(r.id));
    if (!renameCharId || !ids.includes(renameCharId)) {
      setRenameCharId(ids[0] ?? "");
    }
  }, [accountCharacters, renameCharId]);

  useEffect(() => {
    if (pKind !== "temp_ban" && pKind !== "temp_mute") {
      setPExpires("");
    }
  }, [pKind]);

  const loadList = useCallback(async () => {
    setErr(null);
    const term = q.trim();
    const offset = page * PAGE;
    if (term === "") {
      const [c, l] = await Promise.all([
        db.query(ACCOUNTS_COUNT_ALL),
        db.query(ACCOUNTS_LIST_PAGE, [PAGE, offset]),
      ]);
      setTotal(Number(c.rows[0]?.c ?? 0));
      setRows(l.rows);
    } else {
      const pattern = `%${escapeLike(term)}%`;
      const [c, l] = await Promise.all([
        db.query(ACCOUNTS_COUNT_SEARCH, [pattern]),
        db.query(ACCOUNTS_SEARCH_PAGE, [pattern, PAGE, offset]),
      ]);
      setTotal(Number(c.rows[0]?.c ?? 0));
      setRows(l.rows);
    }
  }, [db, q, page]);

  useEffect(() => {
    loadList().catch((e) => setErr(e instanceof Error ? e.message : String(e)));
  }, [loadList]);

  const loadDetail = async (id: number) => {
    setSelectedId(id);
    setSaveInfo(null);
    setCharsNote(null);
    setPunishNote(null);
    setPunishRes(null);
    setPunishInfo(null);
    setRenameInfo(null);
    setRenameInput("");
    setSessionsNote(null);
    setErr(null);
    setDetail(null);
    setSessionsRes(null);
    setCharsRes(null);
    setAccountModalTab("overview");
    setPSelectedCharacterId("");
    setDetailLoading(true);
    try {
      const acc = await db.query(ACCOUNT_BY_ID, [id]);
      const row = acc.rows[0] ?? null;
      setDetail(row);
      setRightsEdit(String(row?.rights ?? "modlevel.player"));
      try {
        const se = await db.query(ACCOUNT_SESSIONS, [id]);
        setSessionsRes({
          type: "result",
          requestId: null,
          command: "sessions",
          rowCount: se.rowCount,
          fields: se.fields,
          rows: se.rows,
        });
        setSessionsNote(null);
      } catch {
        setSessionsRes(null);
        setSessionsNote("No sessions table.");
      }
      try {
        const ch = await db.query(ACCOUNT_CHARACTERS, [id]);
        setCharsRes({
          type: "result",
          requestId: null,
          command: "account_characters",
          rowCount: ch.rowCount,
          fields: ch.fields,
          rows: ch.rows,
        });
        setCharsNote(null);
      } catch {
        setCharsRes(null);
        setCharsNote("No account_characters table.");
      }
      try {
        const pu = await db.query(PUNISHMENTS_FOR_ACCOUNT, [id, id]);
        setPunishRes({
          type: "result",
          requestId: null,
          command: "punishments",
          rowCount: pu.rowCount,
          fields: pu.fields,
          rows: pu.rows,
        });
        setPunishNote(null);
      } catch {
        setPunishRes(null);
        setPunishNote("No punishments table (run latest schema bootstrap).");
      }
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setDetailLoading(false);
    }
  };

  const openAccount = async (id: number) => {
    setDialogOpen(true);
    await loadDetail(id);
  };

  const setPunishmentStatus = async (punishmentId: number, status: string) => {
    if (selectedId == null) {
      return;
    }
    setPunishBusy(true);
    setErr(null);
    setPunishInfo(null);
    try {
      await db.query(PUNISHMENT_UPDATE_STATUS, [status, punishmentId]);
      setPunishInfo(`Punishment ${punishmentId} → ${status}`);
      await loadDetail(selectedId);
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setPunishBusy(false);
    }
  };

  const insertPunishment = async () => {
    if (selectedId == null) {
      return;
    }
    if (pScope === "character") {
      const cid = Number(pSelectedCharacterId);
      if (!Number.isFinite(cid) || cid <= 0 || accountCharacters.length === 0) {
        setErr("Select a character for character scope.");
        return;
      }
    }
    setPunishBusy(true);
    setErr(null);
    setPunishInfo(null);
    try {
      const expires =
        pExpires.trim() === "" || (pKind !== "temp_ban" && pKind !== "temp_mute")
          ? null
          : pExpires.trim();
      const accountId = pScope === "account" ? selectedId : null;
      const characterId = pScope === "character" ? Number(pSelectedCharacterId) : null;
      await db.query(PUNISHMENT_INSERT, [
        pScope,
        accountId,
        characterId,
        pKind,
        expires,
        pReason.trim() || "(no reason)",
        pPriv.trim(),
        pPub.trim(),
        pIssued.trim() || "unknown",
        pApproved.trim(),
      ]);
      setPunishInfo("Punishment inserted.");
      setPReason("");
      setPPriv("");
      setPPub("");
      setPExpires("");
      await loadDetail(selectedId);
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setPunishBusy(false);
    }
  };

  const applyCharacterDisplayName = async () => {
    if (selectedId == null) {
      return;
    }
    const cid = Number(renameCharId);
    if (!Number.isFinite(cid) || cid <= 0) {
      setErr("Select a character to rename.");
      return;
    }
    const raw = renameInput;
    setRenameBusy(true);
    setErr(null);
    setRenameInfo(null);
    try {
      const ctxRes = await db.query(CHARACTER_DISPLAY_NAME_CONTEXT, [cid, selectedId]);
      const ctx = ctxRes.rows[0] as AccountRow | undefined;
      if (!ctx) {
        setErr("Character not found for this account.");
        return;
      }
      const fmtRes = validateStaffDisplayNameFormat(raw, null);
      if (!fmtRes.ok) {
        setErr(fmtRes.message);
        return;
      }
      const sanitized = fmtRes.sanitized;
      const nowMs = Date.now();

      const takenChar = await db.query(DISPLAY_NAME_TAKEN_BY_OTHER_CHARACTER, [sanitized, cid]);
      if (takenChar.rows.length > 0) {
        setErr("Another character already uses this display name.");
        return;
      }
      const takenLogin = await db.query(DISPLAY_NAME_TAKEN_AS_OTHER_LOGIN, [sanitized, selectedId]);
      if (takenLogin.rows.length > 0) {
        setErr("Another account already uses this text as their login username (case-insensitive).");
        return;
      }

      await db.query(CHARACTER_DISPLAY_NAME_APPLY, [cid, selectedId, sanitized, nowMs, true]);
      setRenameInfo("Display name updated.");
      setRenameInput("");
      await loadDetail(selectedId);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      if (msg.includes("duplicate key") || msg.includes("unique")) {
        setErr("That display name is no longer available (unique conflict). Refresh and try again.");
      } else {
        setErr(msg);
      }
    } finally {
      setRenameBusy(false);
    }
  };

  const saveRights = async () => {
    if (selectedId == null) {
      return;
    }
    setBusy(true);
    setErr(null);
    setSaveInfo(null);
    try {
      await db.query(`UPDATE accounts SET rights = $1, updated_at = CURRENT_TIMESTAMP WHERE id = $2`, [rightsEdit, selectedId]);
      setSaveInfo("Saved.");
      await loadList();
      await loadDetail(selectedId);
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const maxPage = Math.max(0, Math.ceil(total / PAGE) - 1);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-2">
        <div className="space-y-1">
          <Label htmlFor="acct-search">Search</Label>
          <Input
            id="acct-search"
            placeholder="login…"
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setPage(0);
            }}
            className="w-[220px]"
          />
        </div>
        <p className="pb-2 text-xs text-muted-foreground">{total} total · page {page + 1}/{maxPage + 1 || 1}</p>
        <Button type="button" variant="outline" size="sm" disabled={page <= 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
          Prev
        </Button>
        <Button type="button" variant="outline" size="sm" disabled={page >= maxPage} onClick={() => setPage((p) => p + 1)}>
          Next
        </Button>
        <Button type="button" variant="secondary" size="sm" onClick={() => loadList().catch((e) => setErr(String(e)))}>
          Refresh
        </Button>
      </div>
      {err && !dialogOpen && <p className="text-sm text-destructive">{err}</p>}
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-24">id</TableHead>
            <TableHead>username</TableHead>
            <TableHead>rights</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => (
            <TableRow key={String(row.id)} className="cursor-pointer" onClick={() => openAccount(Number(row.id))}>
              <TableCell className="font-mono text-xs">{String(row.id)}</TableCell>
              <TableCell>
                <code className="text-sm">{String(row.username)}</code>
              </TableCell>
              <TableCell className="max-w-[220px] truncate text-xs text-muted-foreground">{String(row.rights ?? "")}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      <Dialog
        open={dialogOpen}
        onOpenChange={(o) => {
          setDialogOpen(o);
          if (!o) {
            setErr(null);
            setSaveInfo(null);
            setPunishInfo(null);
            setAccountModalTab("overview");
            setSelectedId(null);
            setDetail(null);
          }
        }}
      >
        <DialogContent
          className={cn(
            "flex w-[min(98vw,72rem)] max-w-none flex-col gap-0 overflow-hidden p-0 sm:max-w-none xl:w-[min(98vw,80rem)]",
            "max-h-[min(92vh,880px)] min-h-0",
            "left-[50%] top-[4vh] translate-x-[-50%] translate-y-0",
            "rounded-2xl border bg-background shadow-2xl",
          )}
        >
          <DialogHeader className="shrink-0 space-y-1 border-b bg-muted/30 px-6 py-4 text-left">
            <DialogTitle className="text-xl font-semibold tracking-tight">
              {detailLoading ? "…" : detail ? String(detail.username) : "Account"}
            </DialogTitle>
            <DialogDescription className="font-mono text-xs text-muted-foreground">
              {detail && !detailLoading ? `Account id ${String(detail.id)}` : "\u00a0"}
            </DialogDescription>
          </DialogHeader>
          <div className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden overscroll-contain px-6 [scrollbar-gutter:stable]">
            <div className="py-5 pb-8">
              {err && <p className="mb-4 text-sm text-destructive">{err}</p>}
              {detailLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
              {!detailLoading && detail && (
                <Tabs value={accountModalTab} onValueChange={(v) => setAccountModalTab(v as "overview" | "punishments")}>
                  <TabsList className="mb-4 w-full justify-start sm:w-auto">
                    <TabsTrigger value="overview">Overview</TabsTrigger>
                    <TabsTrigger value="punishments">Punishments</TabsTrigger>
                  </TabsList>

                  <TabsContent value="overview" className="mt-0 space-y-5">
                    <div className="grid gap-3 sm:grid-cols-2">
                      <div className="rounded-lg border border-border bg-muted/20 px-3 py-2">
                        <p className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">Created</p>
                        <p className="font-mono text-sm">{fmt(detail.created_at)}</p>
                      </div>
                      <div className="rounded-lg border border-border bg-muted/20 px-3 py-2">
                        <p className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">Updated</p>
                        <p className="font-mono text-sm">{fmt(detail.updated_at)}</p>
                      </div>
                    </div>

                    <div className="space-y-2">
                      <Label htmlFor="acct-rights-select">Rights</Label>
                      <Select value={rightsEdit} onValueChange={setRightsEdit}>
                        <SelectTrigger id="acct-rights-select">
                          <SelectValue placeholder="Select rights" />
                        </SelectTrigger>
                        <SelectContent>
                          {rightsOptions.map((v) => (
                            <SelectItem key={v} value={v}>
                              {v}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>

                    <div className="space-y-2">
                      <h4 className="text-sm font-medium">Sessions</h4>
                      {sessionsNote && <p className="text-xs text-amber-500">{sessionsNote}</p>}
                      <ResultTable result={sessionsRes} />
                    </div>

                    <div className="space-y-2">
                      <h4 className="text-sm font-medium">
                        Characters
                        {detail?.username ? (
                          <span className="ml-2 font-mono text-xs text-muted-foreground">{String(detail.username)}</span>
                        ) : null}
                      </h4>
                      {charsNote && <p className="text-xs text-amber-500">{charsNote}</p>}
                      <ResultTable result={charsRes} />
                      {!charsNote && accountCharacters.length > 0 && (
                        <div className="mt-3 space-y-3 rounded-lg border border-border bg-muted/10 p-3">
                          <h5 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                            Change display name
                          </h5>
                          {renameInfo && (
                            <p className="rounded border border-emerald-500/30 bg-emerald-500/10 px-2 py-1 text-[11px] text-emerald-800 dark:text-emerald-300">
                              {renameInfo}
                            </p>
                          )}
                          <div className="grid gap-2 sm:grid-cols-2">
                            <div className="space-y-1">
                              <Label className="text-[10px] font-medium text-muted-foreground">Character</Label>
                              <Select value={renameCharId} onValueChange={setRenameCharId}>
                                <SelectTrigger className="h-9 bg-background text-sm">
                                  <SelectValue placeholder="Character id" />
                                </SelectTrigger>
                                <SelectContent>
                                  {accountCharacters.map((r) => (
                                    <SelectItem key={String(r.id)} value={String(r.id)}>
                                      {String(r.id)} · {fmt(r.display_name)}
                                    </SelectItem>
                                  ))}
                                </SelectContent>
                              </Select>
                            </div>
                            <div className="space-y-1 sm:col-span-2">
                              <Label className="text-[10px] font-medium text-muted-foreground">New display name</Label>
                              <Input
                                className="font-mono text-sm"
                                value={renameInput}
                                onChange={(e) => setRenameInput(e.target.value)}
                                placeholder="Display name"
                                autoComplete="off"
                              />
                            </div>
                          </div>
                          <Button
                            type="button"
                            size="sm"
                            disabled={renameBusy}
                            onClick={() => void applyCharacterDisplayName()}
                          >
                            {renameBusy ? "Applying…" : "Apply display name"}
                          </Button>
                        </div>
                      )}
                    </div>
                  </TabsContent>

                  <TabsContent value="punishments" className="mt-0 space-y-3 focus-visible:outline-none">
                    <p className="text-xs leading-snug text-muted-foreground">
                      Ban and lock gate Central world-link login. Mute does not — you can log in; chat is blocked in-game.
                      Kicks notify game servers once (audit row kept).
                    </p>
                    {punishInfo && (
                      <p className="rounded-md border border-emerald-500/30 bg-emerald-500/10 px-2 py-1.5 text-[11px] text-emerald-700 dark:text-emerald-400">
                        {punishInfo}
                      </p>
                    )}

                    <div className="grid min-h-0 gap-3 lg:grid-cols-2 lg:items-stretch lg:gap-4">
                      <Card className="flex min-h-[min(52vh,380px)] flex-col overflow-hidden border-border/80 shadow-sm lg:min-h-[min(58vh,440px)]">
                        <CardHeader className="shrink-0 space-y-0 border-b bg-muted/20 px-3 py-2">
                          <CardTitle className="text-sm font-semibold leading-tight">History</CardTitle>
                          <CardDescription className="text-[10px] leading-snug text-muted-foreground">
                            Squash = overturned · Deactivate = staff-ended (audit row kept).
                          </CardDescription>
                        </CardHeader>
                        <CardContent className="flex min-h-0 flex-1 flex-col p-0">
                          {punishNote ? (
                            <p className="px-3 py-6 text-center text-xs text-amber-700 dark:text-amber-400">{punishNote}</p>
                          ) : punishRes && punishRes.rows.length > 0 ? (
                            <div className="min-h-0 flex-1 overflow-auto">
                              <Table>
                                <TableHeader>
                                  <TableRow className="hover:bg-transparent">
                                    <TableHead className="h-7 w-10 px-1.5 py-0 font-mono text-[9px] font-semibold uppercase leading-none tracking-wide text-muted-foreground">
                                      id
                                    </TableHead>
                                    <TableHead className="h-7 w-[4.5rem] px-1.5 py-0 text-[9px] font-semibold uppercase leading-none tracking-wide text-muted-foreground">
                                      scope
                                    </TableHead>
                                    <TableHead className="h-7 w-[4.5rem] px-1.5 py-0 text-[9px] font-semibold uppercase leading-none tracking-wide text-muted-foreground">
                                      kind
                                    </TableHead>
                                    <TableHead className="h-7 min-w-[6.5rem] px-1.5 py-0 text-[9px] font-semibold uppercase leading-none tracking-wide text-muted-foreground">
                                      issued
                                    </TableHead>
                                    <TableHead className="h-7 min-w-[4rem] px-1.5 py-0 text-[9px] font-semibold uppercase leading-none tracking-wide text-muted-foreground">
                                      reason
                                    </TableHead>
                                    <TableHead className="h-7 w-[3.25rem] px-1.5 py-0 text-[9px] font-semibold uppercase leading-none tracking-wide text-muted-foreground">
                                      by
                                    </TableHead>
                                    <TableHead className="h-7 w-[6.25rem] px-1.5 py-0 text-left text-[9px] font-semibold uppercase leading-none tracking-wide text-muted-foreground">
                                      actions
                                    </TableHead>
                                  </TableRow>
                                </TableHeader>
                                <TableBody>
                                  {punishRes.rows.map((r) => {
                                    const id = Number(r.id);
                                    const status = String(r.status ?? "");
                                    return (
                                      <TableRow key={String(r.id)} className="border-border/60">
                                        <TableCell className="p-1.5 font-mono text-[11px] tabular-nums leading-tight">
                                          {String(r.id)}
                                        </TableCell>
                                        <TableCell className="p-1.5 text-[11px] capitalize leading-tight">{fmt(r.scope)}</TableCell>
                                        <TableCell className="max-w-[4.5rem] truncate p-1.5 font-mono text-[10px] leading-tight">
                                          {fmt(r.kind)}
                                        </TableCell>
                                        <TableCell className="whitespace-nowrap p-1.5 font-mono text-[10px] leading-tight text-muted-foreground">
                                          {fmt(r.issued_at)}
                                        </TableCell>
                                        <TableCell
                                          className="max-w-[7rem] truncate p-1.5 text-[11px] leading-tight"
                                          title={String(r.reason ?? "")}
                                        >
                                          {fmt(r.reason)}
                                        </TableCell>
                                        <TableCell className="max-w-[3.5rem] truncate p-1.5 text-[11px] leading-tight">
                                          {fmt(r.issued_by)}
                                        </TableCell>
                                        <TableCell className="p-1 text-right align-top">
                                          <div className="flex flex-col items-stretch gap-0.5">
                                            <Button
                                              type="button"
                                              variant="secondary"
                                              size="sm"
                                              title="Mark punishment as squashed (overturned)"
                                              className="h-6 whitespace-normal px-1 py-0 text-left text-[9px] leading-tight"
                                              disabled={punishBusy || status === "squashed"}
                                              onClick={() => setPunishmentStatus(id, "squashed")}
                                            >
                                              Squash
                                            </Button>
                                            <Button
                                              type="button"
                                              variant="outline"
                                              size="sm"
                                              title="Set punishment inactive (staff-ended)"
                                              className="h-6 whitespace-normal px-1 py-0 text-left text-[9px] leading-tight"
                                              disabled={punishBusy || status === "inactive"}
                                              onClick={() => setPunishmentStatus(id, "inactive")}
                                            >
                                              Deactivate
                                            </Button>
                                          </div>
                                        </TableCell>
                                      </TableRow>
                                    );
                                  })}
                                </TableBody>
                              </Table>
                            </div>
                          ) : punishRes && punishRes.rows.length === 0 ? (
                            <p className="px-3 py-8 text-center text-xs text-muted-foreground">No punishments yet.</p>
                          ) : (
                            <p className="px-3 py-8 text-center text-xs text-muted-foreground">Loading history…</p>
                          )}
                        </CardContent>
                      </Card>

                      <Card className="flex min-h-[min(52vh,380px)] flex-col overflow-hidden border-border/80 shadow-sm lg:min-h-[min(58vh,440px)]">
                        <CardHeader className="shrink-0 space-y-0 border-b bg-muted/20 px-3 py-2">
                          <CardTitle className="text-sm font-semibold leading-tight">New punishment</CardTitle>
                          <CardDescription className="text-[10px] leading-snug text-muted-foreground">
                            Temp ban/mute need expiry. Character scope needs a character.
                          </CardDescription>
                        </CardHeader>
                        <CardContent className="flex min-h-0 flex-1 flex-col overflow-hidden p-0">
                          <div className="min-h-0 flex-1 space-y-2.5 overflow-y-auto overscroll-contain px-3 py-3 [scrollbar-gutter:stable]">
                        <div className="grid gap-2.5 sm:grid-cols-2">
                          <div className="space-y-1">
                            <Label className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
                              Scope
                            </Label>
                            <Select value={pScope} onValueChange={(v) => setPScope(v as "account" | "character")}>
                              <SelectTrigger className="h-9 bg-background text-sm">
                                <SelectValue />
                              </SelectTrigger>
                              <SelectContent>
                                <SelectItem value="account">Whole account</SelectItem>
                                <SelectItem value="character" disabled={accountCharacters.length === 0}>
                                  Single character
                                </SelectItem>
                              </SelectContent>
                            </Select>
                            {pScope === "character" && accountCharacters.length === 0 && (
                              <p className="text-xs text-amber-600 dark:text-amber-400">
                                No characters on this account yet.
                              </p>
                            )}
                          </div>
                          <div className="space-y-1">
                            <Label className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
                              Kind
                            </Label>
                            <Select value={pKind} onValueChange={setPKind}>
                              <SelectTrigger className="h-9 bg-background text-sm">
                                <SelectValue />
                              </SelectTrigger>
                              <SelectContent>
                                <SelectItem value="ban">Ban (permanent)</SelectItem>
                                <SelectItem value="temp_ban">Ban (temporary)</SelectItem>
                                <SelectItem value="mute">Mute (permanent)</SelectItem>
                                <SelectItem value="temp_mute">Mute (temporary)</SelectItem>
                                <SelectItem value="locked">Locked (login)</SelectItem>
                                <SelectItem value="kick">Kick online (once)</SelectItem>
                              </SelectContent>
                            </Select>
                          </div>
                        </div>

                        {pScope === "character" && accountCharacters.length > 0 && (
                          <div className="space-y-1">
                            <Label htmlFor="pun-char-select" className="text-[10px] font-medium text-muted-foreground">
                              Character
                            </Label>
                            <Select value={pSelectedCharacterId} onValueChange={setPSelectedCharacterId}>
                              <SelectTrigger id="pun-char-select" className="h-9 bg-background text-sm">
                                <SelectValue placeholder="Choose character" />
                              </SelectTrigger>
                              <SelectContent>
                                {accountCharacters.map((ch) => {
                                  const idStr = String(ch.id);
                                  const name = ch.display_name != null ? String(ch.display_name) : "(unnamed)";
                                  return (
                                    <SelectItem key={idStr} value={idStr}>
                                      {name} · #{idStr}
                                    </SelectItem>
                                  );
                                })}
                              </SelectContent>
                            </Select>
                          </div>
                        )}

                        <div className="grid gap-2.5 sm:grid-cols-2">
                          {(pKind === "temp_ban" || pKind === "temp_mute") && (
                            <div className="space-y-1 sm:col-span-2">
                              <Label htmlFor="pun-exp" className="text-[10px] font-medium text-muted-foreground">
                                Expires
                              </Label>
                              <Input
                                id="pun-exp"
                                type="datetime-local"
                                value={pExpires}
                                onChange={(e) => setPExpires(e.target.value)}
                                className="h-9 w-full max-w-full bg-background font-mono text-xs sm:max-w-md"
                              />
                            </div>
                          )}
                          <div className="space-y-1 sm:col-span-2">
                            <Label htmlFor="pun-reason" className="text-[10px] font-medium text-muted-foreground">
                              Reason <span className="font-normal text-destructive">*</span>
                            </Label>
                            <Textarea
                              id="pun-reason"
                              rows={2}
                              placeholder="Short public-facing reason…"
                              value={pReason}
                              onChange={(e) => setPReason(e.target.value)}
                              className="min-h-[4rem] resize-y bg-background text-sm"
                            />
                          </div>
                          <div className="space-y-1 sm:col-span-2">
                            <Label htmlFor="pun-issued" className="text-[10px] font-medium text-muted-foreground">
                              Issued by
                            </Label>
                            <Input
                              id="pun-issued"
                              value={pIssued}
                              onChange={(e) => setPIssued(e.target.value)}
                              className="h-9 w-full bg-background text-sm"
                            />
                          </div>
                        </div>

                        <details className="group rounded-lg border border-dashed border-border bg-muted/15 px-2.5 py-2">
                          <summary className="cursor-pointer list-none text-[11px] font-medium text-muted-foreground transition-colors hover:text-foreground [&::-webkit-details-marker]:hidden">
                            <span className="inline-flex items-center gap-1.5">
                              <span className="text-[10px] font-semibold uppercase tracking-wide">Optional</span>
                              <span className="text-muted-foreground/80">{"— notes & approval"}</span>
                            </span>
                          </summary>
                          <div className="mt-2 grid gap-2.5 border-t border-border/60 pt-2 sm:grid-cols-2">
                            <div className="space-y-1 sm:col-span-2">
                              <Label htmlFor="pun-priv" className="text-[10px] text-muted-foreground">
                                Private notes
                              </Label>
                              <Textarea
                                id="pun-priv"
                                rows={2}
                                value={pPriv}
                                onChange={(e) => setPPriv(e.target.value)}
                                className="bg-background text-xs"
                              />
                            </div>
                            <div className="space-y-1 sm:col-span-2">
                              <Label htmlFor="pun-pub" className="text-[10px] text-muted-foreground">
                                Public notes
                              </Label>
                              <Textarea
                                id="pun-pub"
                                rows={2}
                                value={pPub}
                                onChange={(e) => setPPub(e.target.value)}
                                className="bg-background text-xs"
                              />
                            </div>
                            <div className="space-y-1 sm:col-span-2">
                              <Label htmlFor="pun-appr" className="text-[10px] text-muted-foreground">
                                Approved by
                              </Label>
                              <Input id="pun-appr" value={pApproved} onChange={(e) => setPApproved(e.target.value)} className="h-9 bg-background text-sm" />
                            </div>
                          </div>
                        </details>
                          </div>
                        </CardContent>
                        <CardFooter className="mt-auto flex w-full shrink-0 flex-row flex-wrap items-center justify-end gap-2 border-t border-border/80 bg-muted/25 px-3 py-2.5">
                          <Button
                            type="button"
                            size="default"
                            className="w-full sm:w-auto sm:min-w-[10rem]"
                            disabled={
                              punishBusy ||
                              detailLoading ||
                              (pScope === "character" && (!pSelectedCharacterId || accountCharacters.length === 0))
                            }
                            onClick={() => insertPunishment()}
                          >
                            {punishBusy ? "Saving…" : "Add punishment"}
                          </Button>
                        </CardFooter>
                    </Card>
                    </div>
                  </TabsContent>
                </Tabs>
              )}
            </div>
          </div>
          <DialogFooter className="shrink-0 gap-2 border-t bg-muted/20 px-6 py-4 sm:justify-end">
            {saveInfo && <span className="mr-auto text-sm text-green-400">{saveInfo}</span>}
            <Button type="button" variant="outline" size="sm" onClick={() => setDialogOpen(false)}>
              Close
            </Button>
            <Button type="button" size="sm" disabled={busy || detailLoading || !selectedId} onClick={saveRights}>
              {busy ? "Saving…" : "Save"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
