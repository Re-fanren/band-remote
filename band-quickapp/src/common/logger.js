/**
 * 统一日志封装
 * 便于在 AIoT-IDE / Vela 调试器中按 TAG 过滤
 */
const TAG = '[BandRemote]'

export function log(...args) {
  console.info(TAG, ...args)
}

export function warn(...args) {
  console.warn(TAG, ...args)
}

export function error(...args) {
  console.error(TAG, ...args)
}

export default { log, warn, error }
