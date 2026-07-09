# Anthem Channel — Analysis

**File:** [`Anthem.xml`](Anthem.xml)
**Engine:** Mirth Connect / NextGen Connect–compatible ("BridgeLink") channel export, schema version `26.3.1`
**Channel name:** `Anthem`
**Initial deploy state:** `STOPPED` (must be started manually after deploy)

## 1. Relationship to the Aetna channel

This channel is a **byte-for-byte structural clone** of [`Aetna.xml`](Aetna.xml) — same source connector, same ~50-rule per-record validator, same 15 destinations in the same order, same error-handling and reprocess logic. A full diff against `Aetna.xml` turns up only MCO-identity substitutions, two whitespace-only comment-marker differences, and export metadata:

| Aspect | Aetna | Anthem |
|---|---|---|
| Channel `id` | `8624283c-…` | `e91819ad-dcb4-4fac-9b2e-e85edde7eac0` |
| Channel `name` | `Aetna` | `Anthem` |
| `mco_code` (globalChannelMap) | `AET` | `ANT` |
| `mco_sftp_folder` | `mco-aetna` | `mco-anthem` |
| Inbound SFTP host | `/lza-prod-esmf/internal/mco-aetna/prod/mirth_inbound` | `/lza-prod-esmf/internal/mco-anthem/prod/mirth_inbound` |
| `exportData.metadata.pruningSettings` block | present (`pruneMetaDataDays=1`, archiving off) | absent from this export |

No validation rule, destination, filter, SQL statement, or error code differs between the two channels. Everything in [Aetna-channel-analysis.md](Aetna-channel-analysis.md) — the record-validation table, the destination-by-destination table, the error-handling/resiliency model, and the list of external dependencies — applies verbatim to Anthem, substituting `mco_code = 'ANT'` and `mco_sftp_folder = 'mco-anthem'` wherever `AET` / `mco-aetna` appear.

## 2. What this channel does (Anthem specifics)

Polls `/lza-prod-esmf/internal/mco-anthem/prod/mirth_inbound` for files named `ANT_MDESMF_<14-digit timestamp>.txt` (or `RS_ANT_MDESMF_<...>_<...>.txt` for resubmissions) dropped by **Anthem**, validates every pipe-delimited member record, and forwards the results:

* Valid records → `Success_<file>` → streamed to NYeC's data lake (`nyec-transfer.nyehealth.org`, `/inbound/esmf/<Month-Year>/`) and archived to `.../archive/mco-anthem/prod/mco_success`.
* Invalid records → `Error_<file>` → copied to Anthem's outbound error SFTP folder and archived to `.../archive/mco-anthem/prod/mco_error`.
* Machine-readable success/rejection receipt files posted back to `${root_sftp_folder}/mco-anthem/esmf/outbound/{success,error}`.
* Email alerts ("GRRHIO Alert: …") sent per the `emailList` config entry keyed by `mco_code = "ANT"`.
* Every file/batch/error tracked in `mco_audit.mco_records` / `mco_record_details` / `mco_record_error_logs`, keyed to the `mco_id` resolved from `mco_audit.mco where mco_code = 'ANT'`.

## 3. Architecture (identical to Aetna)

```
                         ┌───────────────────────────────────────────────────┐
                         │  SFTP inbound: /lza-prod-esmf/.../mco-anthem/     │
                         │  mirth_inbound — File Reader source, polls 60s    │
                         └───────────────────────────┬───────────────────────┘
                                                     │  batched by line-count (chunkSize)
                                                     ▼
                    ┌──────────────────────────────────────────────────────────┐
                    │ Source Transformer (JavaScript) — identical to Aetna:    │
                    │  • filename convention + resubmission detection          │
                    │  • duplicate/already-processed checks against Postgres   │
                    │  • per-record validation (~50 rules, EC0xx error codes)  │
                    │  • builds validData[] / errorMessages[] / status         │
                    │  • dynamically prunes destinationSet based on outcome    │
                    └───────────────────────────┬──────────────────────────────┘
                                                ▼
        Destinations (execution order, all "wait for previous") — see the
        destination table in Aetna-channel-analysis.md §4; behavior, filters,
        and SQL are identical, only mco_code='ANT' / mco_sftp_folder=
        'mco-anthem' vary.
```

## 4. Notable observations

* Same dormant threshold logic (`thresholdValue = 100` ⇒ the `ERRORED` fail-whole-file branch is effectively unreachable — see Aetna analysis §5).
* Same two commented-out EPOP fields (`EPop_Under6`, `EPop_Youth_JJ_FC`) and same silent-catch behavior in `futureDOB`/`isFuturePlanDate`/`isPastEnrollDate`.
* Same disabled backup destination (`Destination 1`, direct key-based SFTP to `nyec-transfer.nyehealth.org`), superseded by the JS-scripted "Stream file to SFTP" destination.
* This export doesn't carry a `pruningSettings` block under `exportData.metadata` — a purely administrative/export-time difference, not a behavioral one.

For full field-by-field validation rules, the destination-by-destination breakdown, error-handling design, and external dependency list (Postgres schema, SFTP paths, SMTP, config-map keys), see **[Aetna-channel-analysis.md](Aetna-channel-analysis.md)**.
