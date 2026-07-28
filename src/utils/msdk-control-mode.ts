import type { MsdkControlStatus } from '/@/api/mavic-3t-control'

export type MsdkControlMode = 'dry' | 'live' | 'unknown'

export const MSDK_MODE_FRESH_MS = 5_000

/**
 * Resolve the bridge mode from a recently observed status snapshot.
 *
 * Fresh telemetry is authoritative, including an explicit null/absent mode.
 * This prevents an older command event (which may also feed the status-level
 * value) from overriding the aircraft's current telemetry. The status-level
 * mode is used only when telemetry is not currently fresh and the bridge's
 * own last-seen signal is recent.
 */
export function resolveMsdkControlMode (
  status: MsdkControlStatus,
  observedAt: number,
  now = Date.now()
): MsdkControlMode {
  if (status.connected !== true ||
      !isFreshAge(now - observedAt)) {
    return 'unknown'
  }

  if (status.telemetry_fresh === true) {
    const telemetryAgeAtObservation =
      isFiniteNonNegative(status.telemetry_age_ms)
        ? status.telemetry_age_ms
        : 0
    if (!isFreshAge(telemetryAgeAtObservation + Math.max(0, now - observedAt))) {
      return 'unknown'
    }
    return modeFromBoolean(status.telemetry?.dry_run)
  }

  if (!isFreshTimestamp(status.last_seen_at, now)) return 'unknown'
  return modeFromBoolean(status.dry_run)
}

function modeFromBoolean (dryRun: boolean | undefined): MsdkControlMode {
  if (dryRun === true) return 'dry'
  if (dryRun === false) return 'live'
  return 'unknown'
}

function isFreshTimestamp (timestamp: number | undefined, now: number): boolean {
  if (!Number.isFinite(timestamp)) return false
  return isFreshAge(now - Number(timestamp))
}

function isFreshAge (age: number): boolean {
  return Number.isFinite(age) && age >= 0 && age <= MSDK_MODE_FRESH_MS
}

function isFiniteNonNegative (value: number | undefined): value is number {
  return Number.isFinite(value) && Number(value) >= 0
}
