<template>
  <div class="control-page">
    <section class="hero">
      <div>
        <p class="eyebrow">DJI MAVIC 3 THERMAL</p>
        <h1>Mavic 3T Flight Control</h1>
        <p class="subtitle">Operator console · RC Pro Enterprise bridge</p>
      </div>
      <div class="connection-card" :class="{ online: status.connected }">
        <span class="status-dot"></span>
        <div>
          <strong>{{ status.connected ? 'RC bridge online' : 'RC bridge offline' }}</strong>
          <small>{{ status.connected ? (status.remote_address || 'Connected') : 'Waiting for Android client' }}</small>
        </div>
        <a-button
          size="small"
          :loading="statusLoading"
          :disabled="controlOperationBusy"
          @click="refreshStatus">
          Refresh
        </a-button>
      </div>
    </section>

    <section class="safety-banner" :class="{ live: controlMode === 'live', unknown: controlMode === 'unknown' }">
      <span class="shield">{{ controlMode === 'dry' ? 'DRY' : (controlMode === 'live' ? 'LIVE' : '?') }}</span>
      <div>
        <strong>{{
          controlMode === 'dry'
            ? 'Safe simulation mode'
            : (controlMode === 'live' ? 'Live aircraft control mode' : 'Control mode unknown')
        }}</strong>
        <p>
          {{
            controlMode === 'dry'
              ? 'Commands are validated by the Android simulator. No physical aircraft will move.'
              : (controlMode === 'live'
                ? 'Commands can reach the physical aircraft. Propellers must remain removed during bench testing.'
                : 'Commands stay disabled until a fresh RC Pro status explicitly reports whether this is dry-run or live mode.')
          }}
        </p>
      </div>
    </section>

    <section v-if="status.connected && !telemetryFresh" class="telemetry-warning">
      <strong>Telemetry is stale</strong>
      <span>No fresh aircraft update has arrived for {{ telemetryAgeLabel }}. Control is disabled until data recovers.</span>
    </section>

    <div class="dashboard-grid">
      <section class="panel telemetry-panel">
        <div class="panel-heading">
          <div>
            <p class="panel-kicker">AIRCRAFT TELEMETRY</p>
            <h2>Live flight data</h2>
          </div>
          <a-tag :color="aircraftOnline ? 'green' : 'red'">
            {{ aircraftOnline ? 'AIRCRAFT ONLINE' : 'AIRCRAFT OFFLINE' }}
          </a-tag>
        </div>
        <div class="telemetry-grid">
          <div><span>Battery</span><strong>{{ telemetryValue(status.telemetry?.battery_percent, '%') }}</strong></div>
          <div><span>Altitude</span><strong>{{ telemetryValue(status.telemetry?.altitude, ' m', 1) }}</strong></div>
          <div><span>Flight mode</span><strong>{{ status.telemetry?.flight_mode || '—' }}</strong></div>
          <div><span>Motors</span><strong>{{ status.telemetry?.motors_on == null ? '—' : (status.telemetry.motors_on ? 'ON' : 'OFF') }}</strong></div>
          <div><span>Latitude</span><strong>{{ telemetryValue(status.telemetry?.latitude, '', 6) }}</strong></div>
          <div><span>Longitude</span><strong>{{ telemetryValue(status.telemetry?.longitude, '', 6) }}</strong></div>
          <div><span>Horizontal speed</span><strong>{{ horizontalSpeed }}</strong></div>
          <div><span>GPS satellites</span><strong>{{ telemetryValue(status.telemetry?.gps_satellite_count) }}</strong></div>
          <div><span>Roll</span><strong>{{ telemetryValue(status.telemetry?.roll, '°', 1) }}</strong></div>
          <div><span>Pitch</span><strong>{{ telemetryValue(status.telemetry?.pitch, '°', 1) }}</strong></div>
          <div><span>Yaw</span><strong>{{ telemetryValue(status.telemetry?.yaw, '°', 1) }}</strong></div>
          <div><span>Vertical speed</span><strong>{{ telemetryValue(status.telemetry?.velocity_z, ' m/s', 1) }}</strong></div>
        </div>
      </section>

      <section class="panel authority-panel">
        <div class="panel-heading">
          <div>
            <p class="panel-kicker">CONTROL AUTHORITY</p>
            <h2>Remote session</h2>
          </div>
          <a-tag :color="authorityColor">
            {{ authorityLabel }}
          </a-tag>
        </div>

        <div class="authority-actions">
          <a-button
            type="primary"
            size="large"
            :disabled="controlOperationBusy || !modeKnown || !status.connected || !aircraftOnline || !telemetryFresh || controlEnabled || hasAnySession"
            :loading="controlOperation === 'ENABLE_CONTROL'"
            @click="enableControl">
            Enable control
          </a-button>
          <a-button
            size="large"
            :disabled="controlOperationBusy || serverReleaseInProgress || !hasOwnedSession || sessionOwnedElsewhere"
            :loading="controlOperation === 'DISABLE_CONTROL'"
            @click="releaseControl">
            Release control
          </a-button>
        </div>

        <dl class="session-details">
          <div><dt>Session</dt><dd>{{ shortSessionId }}</dd></div>
          <div><dt>Last seen</dt><dd>{{ formattedLastSeen }}</dd></div>
          <div><dt>Protocol</dt><dd>WebSocket v1</dd></div>
        </dl>
      </section>

      <section class="panel stick-panel">
        <div class="panel-heading">
          <div>
            <p class="panel-kicker">VIRTUAL STICKS</p>
            <h2>Four-axis input</h2>
          </div>
          <a-button
            size="small"
            :disabled="controlOperationBusy || !canStreamControl"
            @click="centerSticks">
            Center all
          </a-button>
        </div>

        <div class="stick-columns">
          <div class="stick-group">
            <h3>Left stick</h3>
            <label>Yaw <output>{{ sticks.leftHorizontal }}</output></label>
            <a-slider v-model:value="sticks.leftHorizontal" :min="-330" :max="330" @afterChange="centerSticks" />
            <label>Throttle <output>{{ sticks.leftVertical }}</output></label>
            <a-slider v-model:value="sticks.leftVertical" :min="-330" :max="330" @afterChange="centerSticks" />
          </div>
          <div class="stick-group">
            <h3>Right stick</h3>
            <label>Roll <output>{{ sticks.rightHorizontal }}</output></label>
            <a-slider v-model:value="sticks.rightHorizontal" :min="-330" :max="330" @afterChange="centerSticks" />
            <label>Pitch <output>{{ sticks.rightVertical }}</output></label>
            <a-slider v-model:value="sticks.rightVertical" :min="-330" :max="330" @afterChange="centerSticks" />
          </div>
        </div>

        <a-button
          class="send-stick"
          type="primary"
          size="large"
          block
          :disabled="controlOperationBusy || !canStreamControl"
          :loading="sendingType === 'STICK'"
          @click="sendStick">
          Send now (streaming at 10 Hz while enabled)
        </a-button>
      </section>

      <section class="panel result-panel">
        <div class="panel-heading">
          <div>
            <p class="panel-kicker">COMMAND TELEMETRY</p>
            <h2>Latest result</h2>
          </div>
          <a-tag :color="resultColor">{{ lastResult?.status || 'IDLE' }}</a-tag>
        </div>

        <div v-if="lastResult" class="result-content">
          <div class="result-type">{{ lastCommandType }}</div>
          <p>{{ lastResult.message || 'No message supplied.' }}</p>
          <code>{{ lastResult.request_id }}</code>
          <time>{{ formatTime(lastResult.timestamp) }}</time>
        </div>
        <div v-else class="empty-result">
          Send a command to see its acknowledgement.
        </div>
      </section>

      <section class="panel emergency-panel">
        <div>
          <p class="panel-kicker">SAFETY</p>
          <h2>Stop remote control</h2>
          <p>
            {{ controlMode === 'dry'
              ? 'Simulation mode: verifies the stop command without controlling the aircraft.'
              : (controlMode === 'live'
                ? 'Live mode: centers all stick inputs and immediately releases virtual-stick control.'
                : 'Mode is unknown. This safety action only releases an existing owned session.') }}
          </p>
        </div>
        <a-popconfirm
          :title="controlMode === 'dry'
            ? 'Verify the stop command?'
            : 'Immediately stop and release remote control?'"
          ok-text="Send"
          cancel-text="Cancel"
          @confirm="safetyRelease">
          <a-button
            danger
            size="large"
            :disabled="controlOperationBusy || serverReleaseInProgress || !status.connected || !hasOwnedSession || sessionOwnedElsewhere"
            :loading="controlOperation === 'SAFETY_RELEASE'">
            STOP REMOTE CONTROL
          </a-button>
        </a-popconfirm>
      </section>

      <section class="panel audit-panel">
        <div class="panel-heading">
          <div>
            <p class="panel-kicker">AUDIT TRAIL</p>
            <h2>Recent control commands</h2>
          </div>
          <a-button size="small" :loading="auditsLoading" @click="loadAudits">Refresh</a-button>
        </div>
        <div class="audit-table">
          <div class="audit-row audit-header">
            <span>Time</span><span>Command</span><span>Result</span><span>Message</span>
          </div>
          <div v-for="item in audits" :key="item.request_id" class="audit-row">
            <time>{{ formatTime(item.created_at) }}</time>
            <strong>{{ item.command_type }}</strong>
            <a-tag :color="auditColor(item.result_status)">{{ item.result_status }}</a-tag>
            <span :title="item.result_message">{{ item.result_message || '—' }}</span>
          </div>
          <div v-if="!audits.length && !auditsLoading" class="audit-empty">No commands have been recorded.</div>
        </div>
      </section>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  acquireMsdkControlSession,
  getMsdkCommandResult,
  getMsdkControlStatus,
  MsdkCommandType,
  MsdkControlEvent,
  MsdkControlStatus,
  MsdkControlAuditEntry,
  MsdkStickPayload,
  listMsdkControlAudits,
  releaseMsdkControlSession,
  sendMsdkCommand
} from '/@/api/mavic-3t-control'
import { resolveMsdkControlMode } from '/@/utils/msdk-control-mode'

type AuthorityState = 'disabled' | 'enabled' | 'releasing' | 'unknown'
type ControlOperation = 'ENABLE_CONTROL' | 'DISABLE_CONTROL' | 'SAFETY_RELEASE'

interface QueuedStickFrame {
  payload: MsdkStickPayload
  sessionId: string
  observeResult: boolean
  final: boolean
  resolve: Array<(requestId?: string) => void>
}

interface StickQueueOptions {
  sessionId?: string
  observeResult?: boolean
  final?: boolean
}

const status = ref<MsdkControlStatus>({ connected: false })
const statusObservedAt = ref(0)
const modeClock = ref(Date.now())
const statusLoading = ref(false)
const sendingType = ref<MsdkCommandType | ''>('')
const authorityState = ref<AuthorityState>('disabled')
const localControlActive = ref(false)
const controlOperation = ref<ControlOperation | ''>('')
const CONTROL_SESSION_STORAGE_KEY = 'msdk-control-session-id'
const controlSessionId = ref(sessionStorage.getItem(CONTROL_SESSION_STORAGE_KEY) || '')
const lastResult = ref<MsdkControlEvent>()
const lastCommandType = ref('')
const audits = ref<MsdkControlAuditEntry[]>([])
const auditsLoading = ref(false)
const sticks = reactive({
  leftHorizontal: 0,
  leftVertical: 0,
  rightHorizontal: 0,
  rightVertical: 0
})
let statusTimer: ReturnType<typeof setInterval> | undefined
let modeFreshnessTimer: ReturnType<typeof setInterval> | undefined
let stickTimer: ReturnType<typeof setTimeout> | undefined
let stickStreamGeneration = 0
let stickQueueOpen = false
let pendingStickFrame: QueuedStickFrame | undefined
let stickWorkerPromise: Promise<void> | undefined
let statusRefreshInFlight: Promise<void> | undefined
let sessionMutationEpoch = 0
let releaseInFlight: Promise<boolean> | undefined
let releaseRequested = false

const controlEnabled = computed(() => authorityState.value === 'enabled')
const controlOperationBusy = computed(() => controlOperation.value !== '')
const serverReleaseInProgress = computed(() =>
  status.value.control_session_owned === true &&
  status.value.control_session?.releasing === true
)
const hasAnySession = computed(() => Boolean(status.value.control_session))
const authorityLabel = computed(() => {
  if (controlOperation.value === 'ENABLE_CONTROL') return 'ENABLING'
  if (controlOperation.value === 'DISABLE_CONTROL' ||
      controlOperation.value === 'SAFETY_RELEASE') {
    return 'RELEASING'
  }
  switch (authorityState.value) {
    case 'enabled': return 'ENABLED'
    case 'releasing': return 'RELEASING'
    case 'unknown': return 'OUTCOME UNKNOWN'
    default: return 'DISABLED'
  }
})
const authorityColor = computed(() => {
  if (controlOperation.value === 'DISABLE_CONTROL' ||
      controlOperation.value === 'SAFETY_RELEASE') {
    return 'orange'
  }
  switch (authorityState.value) {
    case 'enabled': return 'green'
    case 'releasing': return 'orange'
    case 'unknown': return 'red'
    default: return 'default'
  }
})

const shortSessionId = computed(() => {
  const sessionId = status.value.control_session?.id
  if (!sessionId) return status.value.control_session ? 'Owned by another operator' : '—'
  return sessionId.length > 16
    ? sessionId.slice(0, 8) + '…' + sessionId.slice(-5)
    : sessionId
})

const formattedLastSeen = computed(() =>
  status.value.last_seen_at ? formatTime(status.value.last_seen_at) : '—'
)

const resultColor = computed(() => {
  switch (lastResult.value?.status) {
    case 'ACCEPTED': return 'green'
    case 'PENDING': return 'blue'
    case 'TIMEOUT': return 'orange'
    case 'REJECTED':
    case 'INTERRUPTED': return 'red'
    default: return 'default'
  }
})

const controlMode = computed(() =>
  resolveMsdkControlMode(
    status.value,
    statusObservedAt.value,
    modeClock.value
  )
)
const modeKnown = computed(() => controlMode.value !== 'unknown')
const telemetryFresh = computed(() => status.value.telemetry_fresh === true)
const telemetryAgeLabel = computed(() =>
  status.value.telemetry_age_ms == null
    ? 'an unknown period'
    : `${(status.value.telemetry_age_ms / 1000).toFixed(1)} seconds`
)
const aircraftOnline = computed(() =>
  status.value.connected && status.value.aircraft_connected === true
)
const horizontalSpeed = computed(() => {
  const x = status.value.telemetry?.velocity_x
  const y = status.value.telemetry?.velocity_y
  return x == null || y == null ? '—' : `${Math.hypot(x, y).toFixed(1)} m/s`
})

const hasOwnedSession = computed(() =>
  status.value.control_session_owned === true &&
  Boolean(status.value.control_session?.id) &&
  status.value.control_session?.id === controlSessionId.value
)
const sessionOwnedElsewhere = computed(() => {
  return Boolean(
    status.value.control_session &&
    status.value.control_session_owned === false
  )
})
const canStreamControl = computed(() =>
  modeKnown.value &&
  status.value.connected &&
  aircraftOnline.value &&
  telemetryFresh.value &&
  controlEnabled.value &&
  localControlActive.value &&
  hasOwnedSession.value
)

function auditColor (value: string) {
  if (value === 'ACCEPTED') return 'green'
  if (value === 'PENDING') return 'blue'
  if (value === 'TIMEOUT') return 'orange'
  return 'red'
}

async function loadAudits () {
  auditsLoading.value = true
  try {
    const response = await listMsdkControlAudits(50)
    if (response.code === 0) audits.value = response.data
  } finally {
    auditsLoading.value = false
  }
}

function applyStatusSnapshot (snapshot: MsdkControlStatus) {
  statusObservedAt.value = Date.now()
  modeClock.value = statusObservedAt.value
  status.value = snapshot
  const activeSessionId = snapshot.control_session?.id || ''
  const owned = snapshot.control_session_owned === true

  if (owned && activeSessionId) {
    controlSessionId.value = activeSessionId
    sessionStorage.setItem(CONTROL_SESSION_STORAGE_KEY, activeSessionId)
  } else {
    controlSessionId.value = ''
    sessionStorage.removeItem(CONTROL_SESSION_STORAGE_KEY)
  }

  if (!owned || !activeSessionId) {
    localControlActive.value = false
    pauseStickStream()
    authorityState.value = 'disabled'
  } else if (snapshot.control_session?.releasing === true) {
    localControlActive.value = false
    pauseStickStream()
    authorityState.value = 'releasing'
  } else if (snapshot.control_enabled === true) {
    authorityState.value = 'enabled'
  } else if (snapshot.control_enabled === false) {
    localControlActive.value = false
    pauseStickStream()
    authorityState.value = 'disabled'
  } else {
    localControlActive.value = false
    pauseStickStream()
    authorityState.value = 'unknown'
  }
}

function refreshStatus (): Promise<void> {
  if (statusRefreshInFlight) return statusRefreshInFlight

  const requestEpoch = sessionMutationEpoch
  statusLoading.value = true
  const task = (async () => {
    try {
      const response = await getMsdkControlStatus()
      if (response.code !== 0 ||
          requestEpoch !== sessionMutationEpoch ||
          controlOperationBusy.value) {
        return
      }

      applyStatusSnapshot(response.data)
      if (authorityState.value === 'enabled' &&
          (!response.data.telemetry_fresh ||
           !response.data.aircraft_connected ||
           controlMode.value === 'unknown')) {
        releaseControl(true).catch(() => undefined)
      }
    } catch (error) {
      if (requestEpoch !== sessionMutationEpoch || controlOperationBusy.value) return
      status.value = { ...status.value, connected: false }
      if (authorityState.value === 'enabled') {
        localControlActive.value = false
        pauseStickStream()
        authorityState.value = 'unknown'
        releaseControl(true).catch(() => undefined)
      }
    } finally {
      if (statusRefreshInFlight === task) {
        statusRefreshInFlight = undefined
        statusLoading.value = false
      }
    }
  })()
  statusRefreshInFlight = task
  return task
}

function beginControlOperation (operation: ControlOperation): boolean {
  if (controlOperationBusy.value) return false
  controlOperation.value = operation
  sessionMutationEpoch++
  return true
}

function finishControlOperation (operation: ControlOperation) {
  if (controlOperation.value !== operation) return
  sessionMutationEpoch++
  controlOperation.value = ''
}

async function enableControl () {
  if (controlOperationBusy.value ||
      hasAnySession.value ||
      !modeKnown.value ||
      !aircraftOnline.value ||
      !telemetryFresh.value ||
      !beginControlOperation('ENABLE_CONTROL')) {
    return
  }

  releaseRequested = false
  let acquiredSessionId = ''
  try {
    const acquired = await acquireMsdkControlSession()
    if (acquired.code !== 0 || !acquired.data.id) {
      message.error(acquired.message || 'Control session is already owned or unavailable.')
      return
    }

    acquiredSessionId = acquired.data.id
    controlSessionId.value = acquiredSessionId
    sessionStorage.setItem(CONTROL_SESSION_STORAGE_KEY, acquiredSessionId)
    status.value.control_session = acquired.data
    status.value.control_session_owned = true
    status.value.control_enabled = false

    if (releaseRequested) {
      await releaseSessionAndConfirm(acquiredSessionId, true)
      return
    }

    const enableResult = await dispatchCommand('ENABLE_CONTROL')
    const enabled = enableResult?.status === 'ACCEPTED'
    if (enabled && !releaseRequested) {
      authorityState.value = 'enabled'
      localControlActive.value = true
      status.value.control_enabled = true
      startStickStream()
    } else {
      authorityState.value =
        enableResult?.status === 'REJECTED'
          ? 'disabled'
          : 'unknown'
      await releaseSessionAndConfirm(acquiredSessionId, true)
      message.error('Control enable was not confirmed; the acquired session was released where possible.')
    }
  } catch (error) {
    if (acquiredSessionId) {
      authorityState.value = 'unknown'
      await releaseSessionAndConfirm(acquiredSessionId, true)
    }
    message.error('Control could not be enabled. Any acquired session was released where possible.')
  } finally {
    releaseRequested = false
    finishControlOperation('ENABLE_CONTROL')
    refreshStatus().catch(() => undefined)
  }
}

async function releaseControl (silent = false): Promise<boolean> {
  if (releaseInFlight) return releaseInFlight
  if (controlOperationBusy.value) {
    if (controlOperation.value === 'ENABLE_CONTROL') {
      releaseRequested = true
      localControlActive.value = false
      pauseStickStream()
    }
    return false
  }
  if (!controlSessionId.value) {
    if (!status.value.control_session) authorityState.value = 'disabled'
    return true
  }
  if (!beginControlOperation('DISABLE_CONTROL')) return false

  const sessionId = controlSessionId.value
  releaseInFlight = (async () => {
    try {
      return await releaseSessionAndConfirm(sessionId, silent)
    } finally {
      finishControlOperation('DISABLE_CONTROL')
      releaseInFlight = undefined
      refreshStatus().catch(() => undefined)
    }
  })()
  return releaseInFlight
}

async function releaseSessionAndConfirm (sessionId: string, silent: boolean): Promise<boolean> {
  const previousAuthority = authorityState.value
  localControlActive.value = false
  authorityState.value = 'releasing'
  await closeStickStreamWithCenter(sessionId)

  try {
    const response = await releaseMsdkControlSession(sessionId)
    if (response.code !== 0) {
      authorityState.value =
        previousAuthority === 'enabled' || previousAuthority === 'disabled'
          ? previousAuthority
          : 'unknown'
      if (!silent) {
        message.error('Release was rejected. The session was kept so you can retry safely.')
      }
      return false
    }

    const confirmationDeadline = Date.now() + 6500
    let latestSnapshot: MsdkControlStatus | undefined
    while (Date.now() < confirmationDeadline) {
      await new Promise(resolve => setTimeout(resolve, 250))
      try {
        const latest = await getMsdkControlStatus()
        if (latest.code !== 0) continue
        latestSnapshot = latest.data
        const stillOwned =
          latest.data.control_session_owned === true &&
          latest.data.control_session?.id === sessionId
        if (!stillOwned) {
          applyStatusSnapshot(latest.data)
          clearLocalSession(sessionId)
          return true
        }
        if (latest.data.control_session?.releasing === false) break
      } catch (error) {
        // Keep checking until the bounded confirmation period expires.
      }
    }

    if (!latestSnapshot) {
      authorityState.value = 'unknown'
      if (!silent) {
        message.warning('Release was sent, but its outcome could not be confirmed. You can retry.')
      }
      return false
    }

    applyStatusSnapshot(latestSnapshot)
    if (latestSnapshot.control_enabled == null) authorityState.value = 'unknown'
    if (!silent) {
      message.warning('Release was sent, but the session is still present. You can retry.')
    }
    return false
  } catch (error) {
    authorityState.value = 'unknown'
    if (!silent) {
      message.error('Release outcome is unknown. The session was kept so you can retry safely.')
    }
    return false
  }
}

function clearLocalSession (expectedSessionId?: string) {
  if (expectedSessionId && controlSessionId.value !== expectedSessionId) return
  localControlActive.value = false
  pauseStickStream()
  controlSessionId.value = ''
  sessionStorage.removeItem(CONTROL_SESSION_STORAGE_KEY)
  authorityState.value = 'disabled'
  if (status.value.control_session_owned === true) {
    status.value = {
      ...status.value,
      control_session: undefined,
      control_session_owned: undefined,
      control_enabled: false
    }
  }
}

async function safetyRelease () {
  if (controlOperationBusy.value ||
      !controlSessionId.value ||
      !beginControlOperation('SAFETY_RELEASE')) {
    return
  }

  const sessionId = controlSessionId.value
  const previousAuthority = authorityState.value
  localControlActive.value = false
  authorityState.value = 'releasing'
  try {
    await closeStickStreamWithCenter(sessionId)
    const result = await dispatchCommand('SAFETY_RELEASE', undefined, sessionId)
    if (result?.status === 'ACCEPTED') {
      clearLocalSession(sessionId)
    } else if (result?.status === 'REJECTED') {
      authorityState.value =
        previousAuthority === 'enabled' || previousAuthority === 'disabled'
          ? previousAuthority
          : 'unknown'
      message.error('Safety release was rejected. The session was kept so you can retry.')
    } else {
      authorityState.value = 'unknown'
      message.error('Safety release was not confirmed. The session was kept so you can retry.')
    }
  } catch (error) {
    authorityState.value = 'unknown'
    message.error('Safety release outcome is unknown. The session was kept so you can retry.')
  } finally {
    finishControlOperation('SAFETY_RELEASE')
    refreshStatus().catch(() => undefined)
  }
}

async function sendStick () {
  if (!canStreamControl.value) return
  sendingType.value = 'STICK'
  try {
    const requestId = await enqueueStickFrame({ ...sticks }, { observeResult: true })
    if (!requestId) {
      message.error('Stick command could not be delivered; control release has been requested.')
    }
  } finally {
    if (sendingType.value === 'STICK') sendingType.value = ''
  }
}

async function dispatchCommand (
  type: MsdkCommandType,
  payload?: MsdkStickPayload,
  sessionId = controlSessionId.value
): Promise<MsdkControlEvent | undefined> {
  sendingType.value = type
  lastCommandType.value = type
  try {
    const response = await sendMsdkCommand({
      type,
      payload,
      control_session_id: sessionId || undefined
    })
    if (response.code !== 0 || !response.data.request_id) return
    publishPendingResult(type, response.data.request_id)
    return await pollResult(response.data.request_id)
  } catch (error) {
    message.error('Command delivery or acknowledgement failed; its outcome may be unknown.')
    return undefined
  } finally {
    if (sendingType.value === type) sendingType.value = ''
  }
}

function publishPendingResult (type: MsdkCommandType, requestId: string) {
  lastCommandType.value = type
  lastResult.value = {
    version: 1,
    type: 'COMMAND_ACK',
    request_id: requestId,
    timestamp: Date.now(),
    status: 'PENDING',
    message: 'Waiting for acknowledgement…'
  }
}

async function pollResult (requestId: string): Promise<MsdkControlEvent | undefined> {
  const deadline = Date.now() + 6500
  while (Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 300))
    try {
      const response = await getMsdkCommandResult(requestId)
      if (response.code !== 0 || response.data.request_id !== requestId) continue
      if (lastResult.value?.request_id === requestId) lastResult.value = response.data
      if (response.data.status !== 'PENDING') {
        loadAudits().catch(() => undefined)
        return response.data
      }
    } catch (error) {
      // A transient polling failure does not prove that command dispatch failed.
    }
  }

  const timeout: MsdkControlEvent = {
    version: 1,
    type: 'COMMAND_ACK',
    request_id: requestId,
    timestamp: Date.now(),
    status: 'TIMEOUT',
    message: 'Acknowledgement timed out; the command outcome is unknown.'
  }
  if (lastResult.value?.request_id === requestId) lastResult.value = timeout
  loadAudits().catch(() => undefined)
  return timeout
}

function resetSticks () {
  sticks.leftHorizontal = 0
  sticks.leftVertical = 0
  sticks.rightHorizontal = 0
  sticks.rightVertical = 0
}

function centerSticks () {
  resetSticks()
  if (canStreamControl.value) {
    enqueueStickFrame({ ...sticks }, { observeResult: true }).catch(() => undefined)
  }
}

function startStickStream () {
  pauseStickStream()
  stickQueueOpen = true
  const generation = ++stickStreamGeneration
  scheduleStickFrame(generation, 0)
}

function scheduleStickFrame (generation: number, delay: number) {
  stickTimer = setTimeout(() => {
    stickTimer = undefined
    if (generation !== stickStreamGeneration || !canStreamControl.value) {
      if (hasOwnedSession.value &&
          authorityState.value === 'enabled' &&
          !controlOperationBusy.value) {
        releaseControl(true).catch(() => undefined)
      }
      return
    }

    enqueueStickFrame({ ...sticks }).catch(() => undefined)
    scheduleStickFrame(generation, 100)
  }, delay)
}

function pauseStickStream () {
  stickQueueOpen = false
  stickStreamGeneration++
  if (stickTimer) clearTimeout(stickTimer)
  stickTimer = undefined
  resetSticks()
}

function enqueueStickFrame (
  payload: MsdkStickPayload,
  options: StickQueueOptions = {}
): Promise<string | undefined> {
  const sessionId = options.sessionId || controlSessionId.value
  const final = options.final === true
  if (!sessionId || (!final && (!stickQueueOpen || !canStreamControl.value))) {
    return Promise.resolve(undefined)
  }

  return new Promise(resolve => {
    if (pendingStickFrame && pendingStickFrame.sessionId === sessionId) {
      pendingStickFrame.payload = { ...payload }
      pendingStickFrame.observeResult ||= options.observeResult === true
      pendingStickFrame.final ||= final
      pendingStickFrame.resolve.push(resolve)
    } else {
      if (pendingStickFrame) {
        pendingStickFrame.resolve.forEach(waiter => waiter(undefined))
      }
      pendingStickFrame = {
        payload: { ...payload },
        sessionId,
        observeResult: options.observeResult === true,
        final,
        resolve: [resolve]
      }
    }
    ensureStickWorker()
  })
}

function ensureStickWorker () {
  if (stickWorkerPromise) return
  stickWorkerPromise = runStickWorker().finally(() => {
    stickWorkerPromise = undefined
    if (pendingStickFrame) ensureStickWorker()
  })
}

async function runStickWorker () {
  while (pendingStickFrame) {
    const frame = pendingStickFrame
    pendingStickFrame = undefined
    const valid =
      frame.final ||
      (stickQueueOpen &&
       canStreamControl.value &&
       frame.sessionId === controlSessionId.value)
    if (!valid) {
      frame.resolve.forEach(waiter => waiter(undefined))
      continue
    }

    let requestId: string | undefined
    try {
      const response = await sendMsdkCommand({
        type: 'STICK',
        payload: frame.payload,
        control_session_id: frame.sessionId
      })
      if (response.code !== 0 || !response.data.request_id) {
        throw new Error(response.message || 'Stick command was rejected.')
      }
      requestId = response.data.request_id
      if (frame.observeResult) {
        publishPendingResult('STICK', requestId)
        pollResult(requestId).catch(() => undefined)
      }
    } catch (error) {
      if (!frame.final &&
          frame.sessionId === controlSessionId.value &&
          authorityState.value === 'enabled' &&
          !controlOperationBusy.value) {
        localControlActive.value = false
        authorityState.value = 'unknown'
        pauseStickStream()
        Promise.resolve().then(() => releaseControl(true).catch(() => undefined))
      }
    } finally {
      frame.resolve.forEach(waiter => waiter(requestId))
    }
  }
}

async function closeStickStreamWithCenter (sessionId: string): Promise<boolean> {
  pauseStickStream()
  const requestId = await enqueueStickFrame(
    {
      leftHorizontal: 0,
      leftVertical: 0,
      rightHorizontal: 0,
      rightVertical: 0
    },
    { sessionId, final: true }
  )
  return Boolean(requestId)
}

function handlePageHidden () {
  if (document.visibilityState === 'hidden') {
    releaseControl(true).catch(() => undefined)
  }
}

function formatTime (timestamp: number) {
  return new Date(timestamp).toLocaleTimeString()
}

function telemetryValue (value?: number, suffix = '', digits = 0) {
  return value == null ? '—' : `${value.toFixed(digits)}${suffix}`
}

onMounted(() => {
  modeFreshnessTimer = setInterval(() => {
    modeClock.value = Date.now()
  }, 1000)
  Promise.all([refreshStatus(), loadAudits()]).catch(() => undefined)
  statusTimer = setInterval(() => {
    refreshStatus().catch(() => undefined)
  }, 3000)
  document.addEventListener('visibilitychange', handlePageHidden)
  window.addEventListener('pagehide', handlePageHidden)
})

onBeforeUnmount(() => {
  if (statusTimer) clearInterval(statusTimer)
  if (modeFreshnessTimer) clearInterval(modeFreshnessTimer)
  document.removeEventListener('visibilitychange', handlePageHidden)
  window.removeEventListener('pagehide', handlePageHidden)
  releaseControl(true).catch(() => undefined)
})
</script>

<style lang="scss" scoped>
.control-page {
  height: calc(100vh - 60px);
  min-height: 0;
  overflow-y: scroll;
  scrollbar-color: #466b9f #0a1020;
  scrollbar-width: thin;
  padding: 32px;
  color: #eaf1ff;
  background:
    radial-gradient(circle at 75% 0%, rgba(31, 109, 255, .22), transparent 34%),
    linear-gradient(145deg, #0a1020 0%, #10192d 55%, #0b1222 100%);
}
.control-page::-webkit-scrollbar { width: 10px; }
.control-page::-webkit-scrollbar-track { background: #0a1020; }
.control-page::-webkit-scrollbar-thumb { border: 2px solid #0a1020; border-radius: 8px; background: #466b9f; }
.control-page::-webkit-scrollbar-thumb:hover { background: #5b84bd; }
.hero, .panel-heading, .connection-card, .safety-banner, .authority-actions,
.session-details > div, .emergency-panel {
  display: flex;
  align-items: center;
}
.hero { justify-content: space-between; gap: 24px; margin-bottom: 22px; }
.eyebrow, .panel-kicker {
  margin: 0 0 6px;
  color: #6fa5ff;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .16em;
}
h1 { margin: 0; color: #fff; font-size: 34px; line-height: 1.15; }
h2 { margin: 0; color: #fff; font-size: 20px; }
h3 { color: #dce8ff; font-size: 14px; }
.subtitle { margin: 8px 0 0; color: #8fa2c4; }
.connection-card {
  min-width: 330px;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid #283752;
  border-radius: 12px;
  background: rgba(15, 24, 43, .9);
}
.connection-card > div { flex: 1; }
.connection-card strong, .connection-card small { display: block; }
.connection-card small { margin-top: 3px; color: #8294b4; }
.status-dot { width: 10px; height: 10px; border-radius: 50%; background: #ef5b67; box-shadow: 0 0 12px #ef5b67; }
.connection-card.online .status-dot { background: #35d69f; box-shadow: 0 0 12px #35d69f; }
.safety-banner {
  gap: 13px;
  margin-bottom: 22px;
  padding: 12px 16px;
  border: 1px solid rgba(53, 214, 159, .35);
  border-radius: 10px;
  background: rgba(23, 91, 72, .18);
}
.safety-banner p { margin: 2px 0 0; color: #9abaaa; }
.shield { padding: 5px 8px; border-radius: 6px; color: #07130f; background: #35d69f; font-weight: 800; }
.safety-banner.live {
  border-color: rgba(239, 91, 103, .55);
  background: rgba(125, 27, 39, .24);
}
.safety-banner.live p { color: #efb1b7; }
.safety-banner.live .shield { color: #fff; background: #ef5b67; }
.safety-banner.unknown {
  border-color: rgba(255, 187, 0, .55);
  background: rgba(111, 79, 5, .22);
}
.safety-banner.unknown p { color: #d8c38c; }
.safety-banner.unknown .shield { color: #171205; background: #ffbb00; }
.dashboard-grid { display: grid; grid-template-columns: 1fr 1.45fr; gap: 18px; }
.telemetry-panel { grid-column: 1 / -1; }
.telemetry-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.telemetry-grid > div { display: grid; gap: 5px; padding: 12px; border-radius: 9px; background: #0d1628; }
.telemetry-grid span { color: #7183a2; font-size: 12px; }
.telemetry-grid strong { overflow: hidden; color: #eaf1ff; font-family: monospace; text-overflow: ellipsis; }
.panel {
  padding: 22px;
  border: 1px solid #25334d;
  border-radius: 14px;
  background: rgba(16, 25, 44, .86);
  box-shadow: 0 14px 38px rgba(0, 0, 0, .18);
}
.panel-heading { justify-content: space-between; margin-bottom: 22px; }
.authority-actions { gap: 10px; }
.session-details { margin: 24px 0 0; }
.session-details > div { justify-content: space-between; padding: 10px 0; border-bottom: 1px solid #23314a; }
.session-details dt { color: #7183a2; }
.session-details dd { margin: 0; color: #d9e5fb; font-family: monospace; }
.stick-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 28px; }
.stick-group { padding: 15px; border-radius: 10px; background: #0d1628; }
.stick-group h3 { margin: 0 0 16px; }
.stick-group label { display: flex; justify-content: space-between; color: #91a5c7; }
.stick-group output { color: #70a7ff; font-family: monospace; }
.send-stick { margin-top: 18px; }
.result-content { display: grid; gap: 10px; }
.result-content p { margin: 0; color: #b2c0d9; }
.result-content code { overflow: hidden; color: #80adfa; text-overflow: ellipsis; }
.result-content time { color: #637695; font-size: 12px; }
.result-type { color: #fff; font-size: 18px; font-weight: 650; }
.empty-result { padding: 30px 0; color: #6f82a2; text-align: center; }
.emergency-panel { justify-content: space-between; gap: 24px; border-color: rgba(239, 91, 103, .35); }
.emergency-panel p:last-child { margin: 8px 0 0; color: #8fa2c4; }
.telemetry-warning {
  display: flex; gap: 12px; align-items: center; margin: 0 0 18px; padding: 12px 16px;
  color: #ffd591; border: 1px solid rgba(250,173,20,.45); border-radius: 8px;
  background: rgba(250,173,20,.08);
}
.audit-panel { grid-column: 1 / -1; }
.audit-table { display: grid; gap: 1px; overflow: hidden; border-radius: 8px; background: #24314a; }
.audit-row {
  display: grid; grid-template-columns: 150px 170px 120px minmax(200px, 1fr);
  gap: 14px; align-items: center; min-height: 44px; padding: 8px 14px; background: #111a2d;
}
.audit-row > span:last-child { overflow: hidden; color: #8fa2c4; text-overflow: ellipsis; white-space: nowrap; }
.audit-header { min-height: 36px; color: #7184a8; font-size: 12px; font-weight: 700; }
.audit-empty { padding: 24px; color: #7184a8; text-align: center; background: #111a2d; }

@media (max-width: 960px) {
  .control-page { padding: 20px; }
  .hero { align-items: stretch; flex-direction: column; }
  .connection-card { min-width: 0; }
  .dashboard-grid { grid-template-columns: 1fr; }
  .telemetry-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
