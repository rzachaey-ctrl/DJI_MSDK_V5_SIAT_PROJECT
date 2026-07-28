import request, { IWorkspaceResponse } from '/@/api/http/request'

const API_PREFIX = '/api/v1/msdk/control'

export type MsdkCommandType =
  | 'HEARTBEAT'
  | 'ENABLE_CONTROL'
  | 'DISABLE_CONTROL'
  | 'STICK'
  | 'SAFETY_RELEASE'

export interface MsdkStickPayload {
  leftHorizontal: number
  leftVertical: number
  rightHorizontal: number
  rightVertical: number
}

export interface MsdkControlCommand {
  version?: number
  request_id?: string
  control_session_id?: string
  sequence?: number
  type: MsdkCommandType
  timestamp?: number
  payload?: MsdkStickPayload
}

export interface MsdkControlEvent {
  version: number
  type: string
  request_id: string
  timestamp: number
  status: string
  message: string
  dry_run?: boolean
  latitude?: number
  longitude?: number
  altitude?: number
  velocity_x?: number
  velocity_y?: number
  velocity_z?: number
  roll?: number
  pitch?: number
  yaw?: number
  battery_percent?: number
  flight_mode?: string
  motors_on?: boolean
  gps_satellite_count?: number
}

export interface MsdkControlSession {
  id?: string
  acquired_at: number
  last_heartbeat_at?: number
  next_sequence?: number
  releasing?: boolean
}

export interface MsdkControlStatus {
  connected: boolean
  session_id?: string
  remote_address?: string
  connected_at?: number
  last_seen_at?: number
  last_event?: MsdkControlEvent
  telemetry?: MsdkControlEvent
  aircraft_connected?: boolean
  control_enabled?: boolean
  control_session?: MsdkControlSession
  control_session_owned?: boolean
  telemetry_age_ms?: number
  telemetry_fresh?: boolean
  dry_run?: boolean
}

export interface MsdkControlAuditEntry {
  request_id: string
  control_session_id?: string
  sequence?: number
  command_type: string
  result_status: string
  result_message?: string
  created_at: number
  updated_at: number
}

export async function getMsdkControlStatus (): Promise<IWorkspaceResponse<MsdkControlStatus>> {
  const response = await request.get(`${API_PREFIX}/status`)
  return response.data
}

export async function sendMsdkCommand (
  command: MsdkControlCommand
): Promise<IWorkspaceResponse<MsdkControlCommand>> {
  const response = await request.post(`${API_PREFIX}/commands`, {
    ...command,
    payload: command.payload
      ? {
          left_horizontal: command.payload.leftHorizontal,
          left_vertical: command.payload.leftVertical,
          right_horizontal: command.payload.rightHorizontal,
          right_vertical: command.payload.rightVertical
        }
      : undefined
  })
  return response.data
}

export async function acquireMsdkControlSession (): Promise<IWorkspaceResponse<MsdkControlSession>> {
  const response = await request.post(`${API_PREFIX}/sessions`)
  return response.data
}

export async function releaseMsdkControlSession (sessionId: string): Promise<IWorkspaceResponse<void>> {
  const response = await request.delete(`${API_PREFIX}/sessions/${sessionId}`)
  return response.data
}

export async function getMsdkCommandResult (
  requestId: string
): Promise<IWorkspaceResponse<MsdkControlEvent>> {
  const response = await request.get(`${API_PREFIX}/commands/${requestId}`)
  return response.data
}

export async function listMsdkControlAudits (
  limit = 50
): Promise<IWorkspaceResponse<MsdkControlAuditEntry[]>> {
  const response = await request.get(`${API_PREFIX}/audits`, { params: { limit } })
  return response.data
}
