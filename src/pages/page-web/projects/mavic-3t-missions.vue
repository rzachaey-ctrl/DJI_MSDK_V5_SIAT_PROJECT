<template>
  <div class="mission-page">
    <section class="hero">
      <div>
        <p class="eyebrow">DJI MOBILE SDK V5 · WPML</p>
        <h1>Waypoint Mission Console</h1>
        <p>Upload a KMZ route, transfer it to RC Pro, and control its lifecycle.</p>
      </div>
      <div class="bridge-state">
        <a-tag :color="bridgeOnline ? 'green' : 'red'">
          {{ bridgeOnline ? 'RC BRIDGE ONLINE' : 'RC BRIDGE OFFLINE' }}
        </a-tag>
        <a-tag :color="controlMode === 'dry' ? 'blue' : (controlMode === 'live' ? 'red' : 'orange')">
          {{ controlMode === 'dry' ? 'DRY RUN' : (controlMode === 'live' ? 'LIVE MISSION MODE' : 'MODE UNKNOWN') }}
        </a-tag>
      </div>
    </section>

    <div class="content-grid">
      <section class="panel upload-panel">
        <div class="step-number">01</div>
        <p class="kicker">MISSION FILE</p>
        <h2>Select a KMZ route</h2>
        <p class="hint">The archive must contain <code>wpmz/waylines.wpml</code>.</p>

        <label class="drop-zone" :class="{ selected: selectedFile }">
          <input type="file" accept=".kmz" :disabled="missionOperationInFlight" @change="selectFile">
          <span class="file-icon">KMZ</span>
          <strong>{{ selectedFile?.name || 'Choose a KMZ file' }}</strong>
          <small>{{ selectedFile ? formatBytes(selectedFile.size) : 'Maximum size: 100 MB' }}</small>
        </label>

        <a-button
          type="primary"
          size="large"
          block
          :disabled="!selectedFile || missionOperationInFlight"
          :loading="uploading"
          @click="upload">
          Upload mission
        </a-button>
      </section>

      <section class="panel status-panel">
        <div class="step-number">02</div>
        <div class="status-heading">
          <div>
            <p class="kicker">TASK STATUS</p>
            <h2>{{ mission?.originalFileName || 'No active mission' }}</h2>
          </div>
          <a-tag :color="statusColor">{{ mission?.status || 'IDLE' }}</a-tag>
        </div>

        <div v-if="mission" class="status-body">
          <div class="progress-track">
            <span :style="{ width: progress + '%' }"></span>
          </div>
          <dl>
            <div><dt>Task ID</dt><dd>{{ mission.taskId }}</dd></div>
            <div><dt>File size</dt><dd>{{ formatBytes(mission.fileSize) }}</dd></div>
            <div><dt>Wayline</dt><dd>{{ mission.waylineId ?? '—' }}</dd></div>
            <div><dt>Waypoint</dt><dd>{{ mission.waypointIndex ?? '—' }}</dd></div>
            <div><dt>Updated</dt><dd>{{ formatTime(mission.updatedAt) }}</dd></div>
          </dl>
          <p class="message">{{ mission.message || statusDescription }}</p>
        </div>
        <div v-else class="empty">
          Upload a KMZ file to create a mission task.
        </div>
      </section>

      <section class="panel actions-panel">
        <div class="step-number">03</div>
        <p class="kicker">MISSION LIFECYCLE</p>
        <h2>Transfer and execute</h2>

        <div class="action-grid">
          <a-button
            size="large"
            :disabled="missionOperationInFlight || !canPrepare || !modeKnown || !bridgeOnline || (controlMode === 'live' && (!aircraftOnline || !telemetryFresh))"
            :loading="actionLoading === 'prepare'"
            @click="action('prepare')">
            1. Prepare on RC Pro
          </a-button>
          <a-button
            type="primary"
            size="large"
            :disabled="missionOperationInFlight || !canStart || !modeKnown || !bridgeOnline || (controlMode === 'live' && (!aircraftOnline || !telemetryFresh))"
            :loading="actionLoading === 'start'"
            @click="action('start')">
            2. Start mission
          </a-button>
          <a-button
            size="large"
            :disabled="missionOperationInFlight || mission?.status !== 'EXECUTING' || !bridgeOnline"
            :loading="actionLoading === 'pause'"
            @click="action('pause')">
            Pause
          </a-button>
          <a-button
            size="large"
            :disabled="missionOperationInFlight || mission?.status !== 'PAUSED' || !modeKnown || !bridgeOnline || (controlMode === 'live' && (!aircraftOnline || !telemetryFresh))"
            :loading="actionLoading === 'resume'"
            @click="action('resume')">
            Resume
          </a-button>
        </div>

        <a-popconfirm
          title="Stop the current mission?"
          ok-text="Stop"
          cancel-text="Cancel"
          @confirm="action('stop')">
          <a-button
            danger
            size="large"
            block
            :disabled="missionOperationInFlight || !canStop || !bridgeOnline"
            :loading="actionLoading === 'stop'">
            Stop mission
          </a-button>
        </a-popconfirm>

        <p class="safety-note">
          {{ controlMode === 'dry'
            ? 'Dry-run mode downloads and validates the KMZ but does not upload it to an aircraft.'
            : (controlMode === 'live'
              ? 'Live mode can upload and execute the KMZ on the connected aircraft. Verify the route and test area before starting.'
              : 'Mission actions are disabled until a fresh RC Pro status explicitly reports dry-run or live mode.') }}
        </p>
      </section>

      <section class="panel history-panel">
        <div class="history-heading">
          <div>
            <p class="kicker">RECENT TASKS</p>
            <h2>Mission history</h2>
          </div>
          <a-button
            size="small"
            :disabled="missionOperationInFlight"
            :loading="historyLoading"
            @click="loadHistory">
            Refresh
          </a-button>
        </div>
        <div v-if="history.length" class="history-list">
          <button
            v-for="item in history"
            :key="item.taskId"
            :class="{ active: item.taskId === mission?.taskId }"
            :disabled="missionOperationInFlight"
            @click="selectMission(item)">
            <span class="history-file">{{ item.originalFileName }}</span>
            <span class="history-status">{{ item.status }}</span>
            <span>{{ formatBytes(item.fileSize) }}</span>
            <time>{{ formatTime(item.updatedAt) }}</time>
          </button>
        </div>
        <div v-else class="history-empty">No mission tasks have been uploaded.</div>
      </section>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  getMsdkMission,
  listMsdkMissions,
  MsdkMission,
  sendMsdkMissionAction,
  uploadMsdkMission
} from '/@/api/mavic-3t-missions'
import {
  getMsdkCommandResult,
  getMsdkControlStatus,
  MsdkControlStatus
} from '/@/api/mavic-3t-control'
import { resolveMsdkControlMode } from '/@/utils/msdk-control-mode'

type MissionAction = 'prepare' | 'start' | 'pause' | 'resume' | 'stop'
type ActionOutcomeKind = 'accepted' | 'rejected' | 'ended' | 'unknown' | 'stale'

interface ActionOutcome {
  kind: ActionOutcomeKind
  status?: string
  detail?: string
}

const ACTION_LABELS: Record<MissionAction, string> = {
  prepare: 'Prepare',
  start: 'Start',
  pause: 'Pause',
  resume: 'Resume',
  stop: 'Stop'
}
const COMMAND_ACCEPTED_STATES: Record<MissionAction, string[]> = {
  prepare: ['DOWNLOADING', 'UPLOADING_TO_AIRCRAFT', 'READY'],
  start: ['EXECUTING'],
  pause: ['PAUSED'],
  resume: ['EXECUTING'],
  stop: ['INTERRUPTED']
}
const MISSION_SUCCESS_STATES: Record<MissionAction, MsdkMission['status'][]> = {
  prepare: ['READY'],
  start: ['EXECUTING', 'FINISHED'],
  pause: ['PAUSED'],
  resume: ['EXECUTING', 'FINISHED'],
  stop: ['INTERRUPTED']
}
const POLLED_STATES: MsdkMission['status'][] = [
  'DOWNLOADING',
  'UPLOADING_TO_AIRCRAFT',
  'EXECUTING'
]
const TERMINAL_STATES: MsdkMission['status'][] = [
  'FINISHED',
  'FAILED',
  'INTERRUPTED'
]
const ACTION_OUTCOME_TIMEOUT_MS = 7000
const PREPARATION_POLL_LIMIT_MS = 5 * 60 * 1000
const EXECUTION_POLL_LIMIT_MS = 30 * 60 * 1000

const selectedFile = ref<File>()
const mission = ref<MsdkMission>()
const uploading = ref(false)
const actionLoading = ref<MissionAction | ''>('')
const history = ref<MsdkMission[]>([])
const historyLoading = ref(false)
const controlStatus = ref<MsdkControlStatus>({ connected: false })
const controlStatusObservedAt = ref(0)
const modeClock = ref(Date.now())
const missionOperationInFlight = computed(() =>
  uploading.value || actionLoading.value !== '' || historyLoading.value
)
const refreshRequests = new Map<string, Promise<MsdkMission | undefined>>()
let pollingTimer: ReturnType<typeof setTimeout> | undefined
let bridgeTimer: ReturnType<typeof setInterval> | undefined
let modeFreshnessTimer: ReturnType<typeof setInterval> | undefined
let pollingTaskId: string | undefined
let pollingEpoch = 0
let pollingDeadline = 0
let pollingGeneration = 0
let selectionEpoch = 0
let refreshSequence = 0
let lastAppliedRefreshSequence = 0
let historyRequestSequence = 0
let bridgeRefreshInFlight = false
let disposed = false

const bridgeOnline = computed(() => controlStatus.value.connected === true)
const aircraftOnline = computed(() =>
  bridgeOnline.value && controlStatus.value.aircraft_connected === true
)
const controlMode = computed(() =>
  resolveMsdkControlMode(
    controlStatus.value,
    controlStatusObservedAt.value,
    modeClock.value
  )
)
const modeKnown = computed(() => controlMode.value !== 'unknown')
const telemetryFresh = computed(() => controlStatus.value.telemetry_fresh === true)

const canPrepare = computed(() =>
  !!mission.value &&
  ['PENDING', 'FAILED', 'INTERRUPTED', 'FINISHED', 'READY'].includes(mission.value.status)
)
const canStart = computed(() => mission.value?.status === 'READY')
const canStop = computed(() =>
  !!mission.value && ['EXECUTING', 'PAUSED'].includes(mission.value.status)
)
const statusColor = computed(() => {
  switch (mission.value?.status) {
    case 'READY': return 'cyan'
    case 'EXECUTING': return 'green'
    case 'PAUSED': return 'orange'
    case 'FAILED':
    case 'INTERRUPTED': return 'red'
    case 'DOWNLOADING':
    case 'UPLOADING_TO_AIRCRAFT': return 'blue'
    default: return 'default'
  }
})
const progress = computed(() => {
  switch (mission.value?.status) {
    case 'PENDING': return 12
    case 'DOWNLOADING': return 35
    case 'UPLOADING_TO_AIRCRAFT': return 58
    case 'READY': return 72
    case 'EXECUTING':
    case 'PAUSED': return 88
    case 'FINISHED': return 100
    default: return 0
  }
})
const statusDescription = computed(() => {
  if (!mission.value) return ''
  return {
    PENDING: 'Mission file is stored on the server.',
    DOWNLOADING: 'RC Pro is downloading the KMZ.',
    UPLOADING_TO_AIRCRAFT: 'RC Pro is transferring the mission to the aircraft.',
    READY: 'Mission is ready to start.',
    EXECUTING: 'The waypoint mission is running.',
    PAUSED: 'The waypoint mission is paused.',
    INTERRUPTED: 'The mission was stopped or interrupted.',
    FINISHED: 'The mission completed.',
    FAILED: 'The mission failed. Review the message and prepare it again.'
  }[mission.value.status]
})

function selectFile (event: Event) {
  if (missionOperationInFlight.value) return
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0]
}

async function upload () {
  if (!selectedFile.value || missionOperationInFlight.value) return
  stopPolling()
  selectionEpoch++
  lastAppliedRefreshSequence = 0
  uploading.value = true
  try {
    const response = await uploadMsdkMission(selectedFile.value)
    if (response.code !== 0) return
    if (disposed) return
    setSelectedMission(response.data)
    await loadHistory()
    if (disposed) return
    message.success('KMZ mission uploaded.')
    syncPollingForSelection()
  } catch (error) {
    if (!disposed) message.error('Mission upload failed.')
  } finally {
    uploading.value = false
    syncPollingForSelection()
  }
}

async function action (name: MissionAction) {
  if (!mission.value || missionOperationInFlight.value) return
  const taskId = mission.value.taskId
  const startingStatus = mission.value.status
  const startingUpdatedAt = mission.value.updatedAt
  stopPolling()
  selectionEpoch++
  lastAppliedRefreshSequence = 0
  const actionEpoch = selectionEpoch
  actionLoading.value = name
  try {
    const response = await sendMsdkMissionAction(taskId, name)
    if (!selectionIsCurrent(taskId, actionEpoch)) return
    if (response.code !== 0) {
      const detail = response.message ? `: ${response.message}` : '.'
      message.error(`${ACTION_LABELS[name]} was rejected by the server${detail}`)
      await refreshTask(taskId, actionEpoch)
      return
    }
    if (!response.data ||
        response.data.task_id !== taskId ||
        !response.data.request_id) {
      message.error(`${ACTION_LABELS[name]} returned an invalid command receipt.`)
      return
    }

    const outcome = await waitForActionOutcome(
      taskId,
      name,
      response.data.request_id,
      actionEpoch,
      startingStatus,
      startingUpdatedAt
    )
    if (outcome.kind === 'stale' || disposed) return

    if (outcome.kind === 'accepted') {
      const detail = outcome.detail ? `: ${outcome.detail}` : '.'
      message.success(`${ACTION_LABELS[name]} accepted by RC Pro${detail}`)
    } else if (outcome.kind === 'rejected') {
      const result = outcome.status === 'FAILED'
        ? 'failed on RC Pro'
        : 'was rejected by RC Pro'
      const detail = outcome.detail ? `: ${outcome.detail}` : '.'
      message.error(`${ACTION_LABELS[name]} ${result}${detail}`)
    } else if (outcome.kind === 'ended') {
      message.warning(
        `${ACTION_LABELS[name]} was not confirmed; mission state is now ${outcome.status}.`
      )
    } else {
      message.warning(
        `${ACTION_LABELS[name]} was sent, but its outcome is still unknown. Check RC Pro before retrying.`
      )
    }
  } catch (error) {
    await refreshTask(taskId, actionEpoch)
    if (!disposed && selectionIsCurrent(taskId, actionEpoch)) {
      message.error(`Unable to ${name} the mission. Is the RC bridge online?`)
    }
  } finally {
    if (actionLoading.value === name) actionLoading.value = ''
    if (selectionIsCurrent(taskId, actionEpoch)) syncPollingForSelection()
  }
}

async function waitForActionOutcome (
  taskId: string,
  name: MissionAction,
  requestId: string,
  actionEpoch: number,
  startingStatus: MsdkMission['status'],
  startingUpdatedAt: number
): Promise<ActionOutcome> {
  const deadline = Date.now() + ACTION_OUTCOME_TIMEOUT_MS
  while (Date.now() < deadline) {
    await delay(Math.min(350, deadline - Date.now()))
    if (!selectionIsCurrent(taskId, actionEpoch)) return { kind: 'stale' }

    const [commandOutcome, snapshot] = await Promise.all([
      readCommandOutcome(requestId, name),
      refreshTask(taskId, actionEpoch)
    ])
    if (!selectionIsCurrent(taskId, actionEpoch)) return { kind: 'stale' }

    const missionOutcome = snapshot
      ? evaluateMissionOutcome(name, snapshot, startingStatus, startingUpdatedAt)
      : undefined
    if (missionOutcome?.kind === 'rejected') return missionOutcome
    if (commandOutcome?.kind === 'rejected') return commandOutcome
    if (missionOutcome?.kind === 'accepted') return missionOutcome
    if (commandOutcome) return commandOutcome
    if (missionOutcome) return missionOutcome
  }
  return { kind: 'unknown' }
}

async function readCommandOutcome (
  requestId: string,
  name: MissionAction
): Promise<ActionOutcome | undefined> {
  try {
    const response = await getMsdkCommandResult(requestId)
    if (response.code !== 0 ||
        !response.data ||
        response.data.request_id !== requestId) {
      return undefined
    }
    const status = response.data.status.toUpperCase()
    if (status === 'PENDING') return undefined

    if (response.data.type !== 'MISSION_STATE') {
      if (status === 'TIMEOUT' || status === 'INTERRUPTED') {
        return { kind: 'unknown', status, detail: response.data.message }
      }
      return { kind: 'rejected', status, detail: response.data.message }
    }
    if (status === 'FAILED' || status === 'ERROR' || status === 'REJECTED') {
      return { kind: 'rejected', status, detail: response.data.message }
    }
    if (COMMAND_ACCEPTED_STATES[name].includes(status)) {
      return { kind: 'accepted', status, detail: response.data.message }
    }
    return { kind: 'unknown', status, detail: response.data.message }
  } catch (error) {
    return undefined
  }
}

function evaluateMissionOutcome (
  name: MissionAction,
  snapshot: MsdkMission,
  startingStatus: MsdkMission['status'],
  startingUpdatedAt: number
): ActionOutcome | undefined {
  const changed = snapshot.status !== startingStatus ||
    snapshot.updatedAt > startingUpdatedAt
  if (!changed) return undefined
  if (snapshot.status === 'FAILED') {
    return { kind: 'rejected', status: snapshot.status, detail: snapshot.message }
  }
  if (MISSION_SUCCESS_STATES[name].includes(snapshot.status)) {
    return { kind: 'accepted', status: snapshot.status, detail: snapshot.message }
  }
  if (TERMINAL_STATES.includes(snapshot.status)) {
    return { kind: 'ended', status: snapshot.status, detail: snapshot.message }
  }
  return undefined
}

async function refreshTask (
  taskId: string,
  requestEpoch: number
): Promise<MsdkMission | undefined> {
  const key = `${taskId}:${requestEpoch}`
  const existing = refreshRequests.get(key)
  if (existing) return existing

  const sequence = ++refreshSequence
  const pending = (async () => {
    try {
      const response = await getMsdkMission(taskId)
      if (response.code !== 0 || response.data.taskId !== taskId) return undefined
      mergeHistorySnapshot(response.data)
      if (selectionIsCurrent(taskId, requestEpoch) &&
          sequence > lastAppliedRefreshSequence) {
        lastAppliedRefreshSequence = sequence
        mission.value = response.data
      }
      return response.data
    } catch (error) {
      return undefined
    }
  })()
  refreshRequests.set(key, pending)
  try {
    return await pending
  } finally {
    if (refreshRequests.get(key) === pending) refreshRequests.delete(key)
  }
}

async function loadHistory () {
  if (historyLoading.value) return
  const requestSequence = ++historyRequestSequence
  const requestEpoch = selectionEpoch
  const selectedTaskId = mission.value?.taskId
  historyLoading.value = true
  try {
    const response = await listMsdkMissions()
    if (disposed ||
        requestSequence !== historyRequestSequence ||
        response.code !== 0) {
      return
    }
    history.value = response.data
    if (!selectedTaskId && !mission.value && response.data.length) {
      const recovered = response.data.find(item =>
        POLLED_STATES.includes(item.status) || item.status === 'PAUSED'
      ) || response.data[0]
      setSelectedMission(recovered)
      syncPollingForSelection()
    } else if (selectedTaskId && selectionIsCurrent(selectedTaskId, requestEpoch)) {
      const selected = response.data.find(item => item.taskId === selectedTaskId)
      if (selected && selected.updatedAt >= (mission.value?.updatedAt ?? 0)) {
        mission.value = selected
        syncPollingForSelection()
      }
    }
  } catch (error) {
    if (!disposed) message.error('Unable to load mission history.')
  } finally {
    if (requestSequence === historyRequestSequence) {
      historyLoading.value = false
    }
  }
}

async function refreshBridgeStatus () {
  if (bridgeRefreshInFlight) return
  bridgeRefreshInFlight = true
  try {
    const response = await getMsdkControlStatus()
    if (!disposed && response.code === 0) {
      controlStatusObservedAt.value = Date.now()
      modeClock.value = controlStatusObservedAt.value
      controlStatus.value = response.data
    }
  } catch (error) {
    if (!disposed) controlStatus.value = { connected: false }
  } finally {
    bridgeRefreshInFlight = false
  }
}

function selectMission (item: MsdkMission) {
  if (missionOperationInFlight.value) return
  setSelectedMission(item)
  syncPollingForSelection()
}

function setSelectedMission (item: MsdkMission) {
  stopPolling()
  selectionEpoch++
  lastAppliedRefreshSequence = 0
  mission.value = item
}

function selectionIsCurrent (taskId: string, requestEpoch: number) {
  return !disposed &&
    selectionEpoch === requestEpoch &&
    mission.value?.taskId === taskId
}

function mergeHistorySnapshot (snapshot: MsdkMission) {
  const index = history.value.findIndex(item => item.taskId === snapshot.taskId)
  if (index < 0) {
    history.value = [snapshot, ...history.value].slice(0, 20)
  } else if (snapshot.updatedAt >= history.value[index].updatedAt) {
    history.value[index] = snapshot
  }
}

function syncPollingForSelection () {
  if (!mission.value || !POLLED_STATES.includes(mission.value.status)) {
    stopPolling()
    return
  }
  startPolling(mission.value.taskId)
}

function startPolling (taskId: string) {
  if (disposed ||
      mission.value?.taskId !== taskId ||
      !POLLED_STATES.includes(mission.value.status)) {
    stopPolling()
    return
  }
  if (pollingTaskId === taskId &&
      pollingEpoch === selectionEpoch &&
      pollingDeadline > Date.now()) {
    return
  }

  stopPolling()
  pollingTaskId = taskId
  pollingEpoch = selectionEpoch
  pollingDeadline = Date.now() + (
    mission.value.status === 'EXECUTING'
      ? EXECUTION_POLL_LIMIT_MS
      : PREPARATION_POLL_LIMIT_MS
  )
  schedulePolling()
}

function schedulePolling () {
  const taskId = pollingTaskId
  const requestEpoch = pollingEpoch
  const generation = pollingGeneration
  if (!taskId || !selectionIsCurrent(taskId, requestEpoch)) {
    stopPolling()
    return
  }
  if (!mission.value || !POLLED_STATES.includes(mission.value.status)) {
    stopPolling()
    return
  }
  if (Date.now() >= pollingDeadline) {
    stopPolling()
    message.warning('Automatic mission updates paused. Use Refresh to fetch the latest state.')
    return
  }

  const interval = mission.value.status === 'EXECUTING' ? 2000 : 1000
  pollingTimer = setTimeout(async () => {
    pollingTimer = undefined
    const snapshot = await refreshTask(taskId, requestEpoch)
    if (pollingGeneration !== generation ||
        pollingTaskId !== taskId ||
        pollingEpoch !== requestEpoch ||
        !selectionIsCurrent(taskId, requestEpoch)) {
      return
    }
    if (snapshot && !POLLED_STATES.includes(snapshot.status)) {
      stopPolling()
      return
    }
    schedulePolling()
  }, interval)
}

function stopPolling () {
  if (pollingTimer) clearTimeout(pollingTimer)
  pollingGeneration++
  pollingTimer = undefined
  pollingTaskId = undefined
  pollingEpoch = 0
  pollingDeadline = 0
}

function delay (milliseconds: number) {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}

function formatBytes (bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatTime (timestamp: number) {
  return new Date(timestamp).toLocaleString()
}

onBeforeUnmount(() => {
  disposed = true
  selectionEpoch++
  stopPolling()
  if (bridgeTimer) clearInterval(bridgeTimer)
  if (modeFreshnessTimer) clearInterval(modeFreshnessTimer)
})

onMounted(() => {
  modeFreshnessTimer = setInterval(() => {
    modeClock.value = Date.now()
  }, 1000)
  bridgeTimer = setInterval(() => {
    refreshBridgeStatus()
  }, 3000)
  refreshBridgeStatus()
  loadHistory()
})
</script>

<style scoped lang="scss">
.mission-page {
  min-height: calc(100vh - 60px);
  padding: 34px 42px;
  color: #eef3f8;
  background:
    radial-gradient(circle at 84% 10%, rgba(45, 140, 240, .17), transparent 28%),
    #0d1117;
}
.hero {
  display: flex; justify-content: space-between; align-items: flex-start;
  max-width: 1320px; margin: 0 auto 28px;
  h1 { margin: 4px 0 8px; color: white; font-size: 32px; }
  p { margin: 0; color: #8e9bab; }
}
.bridge-state { display: flex; gap: 8px; }
.eyebrow, .kicker {
  color: #4a9fff !important; font-size: 12px; font-weight: 700; letter-spacing: 1.5px;
}
.content-grid {
  display: grid; grid-template-columns: 1fr 1.25fr; gap: 20px;
  max-width: 1320px; margin: auto;
}
.panel {
  position: relative; padding: 28px; overflow: hidden;
  border: 1px solid #27313d; border-radius: 12px; background: #151b23;
  h2 { margin: 5px 0 10px; color: white; font-size: 21px; }
}
.status-panel { grid-row: span 2; }
.step-number {
  position: absolute; right: 20px; top: 12px;
  color: rgba(255,255,255,.05); font-size: 60px; font-weight: 800;
}
.hint, .safety-note { color: #8390a0; }
code { color: #70b6ff; }
.drop-zone {
  display: flex; flex-direction: column; align-items: center; gap: 6px;
  margin: 22px 0; padding: 28px; cursor: pointer;
  border: 1px dashed #425064; border-radius: 10px; background: #10151c;
  &.selected { border-color: #2d8cf0; background: rgba(45,140,240,.08); }
  input { display: none; }
  small { color: #7e8b9a; }
}
.file-icon {
  padding: 8px; color: #63adff; border: 1px solid #2d8cf0; border-radius: 5px;
  font-size: 11px; font-weight: 800;
}
.status-heading { display: flex; justify-content: space-between; align-items: start; }
.progress-track {
  height: 7px; margin: 26px 0; overflow: hidden; border-radius: 5px; background: #29313c;
  span { display: block; height: 100%; transition: width .4s; background: #2d8cf0; }
}
dl {
  display: grid; grid-template-columns: 1fr 1fr; gap: 18px;
  div { min-width: 0; }
  dt { color: #758292; font-size: 12px; }
  dd { margin: 4px 0 0; overflow: hidden; color: #dce6f1; text-overflow: ellipsis; }
}
.message, .empty {
  margin-top: 25px; padding: 16px; color: #aebdcb;
  border-left: 3px solid #2d8cf0; background: #10151c;
}
.empty { min-height: 180px; display: flex; align-items: center; justify-content: center; border-left: 0; }
.action-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 22px 0 12px; }
.safety-note { margin: 18px 0 0; font-size: 12px; text-align: center; }
.history-panel { grid-column: 1 / -1; }
.history-heading { display: flex; align-items: start; justify-content: space-between; }
.history-list {
  display: grid; gap: 8px; margin-top: 18px;
  button {
    display: grid; grid-template-columns: 2fr 1fr .7fr 1.2fr; gap: 16px;
    align-items: center; padding: 13px 16px; color: #8f9cab; text-align: left;
    border: 1px solid transparent; border-radius: 7px; background: #10151c; cursor: pointer;
    &:hover, &.active { color: #dfeaf5; border-color: #2d8cf0; }
  }
}
.history-file { overflow: hidden; color: #e1eaf4; text-overflow: ellipsis; white-space: nowrap; }
.history-status { color: #63adff; font-size: 12px; font-weight: 700; }
.history-empty { padding: 28px; color: #788594; text-align: center; background: #10151c; }
@media (max-width: 900px) {
  .content-grid { grid-template-columns: 1fr; }
  .status-panel { grid-row: auto; }
  .history-list button { grid-template-columns: 1fr 1fr; }
}
</style>
