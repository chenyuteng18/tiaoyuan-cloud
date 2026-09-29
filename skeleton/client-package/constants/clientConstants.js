/**
 * Client-visible constants.
 *
 * Rule of thumb for this package: a constant is a value the client is allowed
 * to read, never a rule the client is allowed to evaluate. Anything that looks
 * like a judgement threshold, a weight, or a verdict belongs server-side.
 */
'use strict';

const CLIENT_API_BASE_PATH = '/api/v1';

const CLIENT_SYNC_POLL_INTERVAL_MS = 5 * 60 * 1000;

// Band display window: the client shows a positive count of collected days.
// A missing day is simply absent; it is never substituted with a zero.
const CLIENT_BAND_DISPLAY_DAYS = 14;

const CLIENT_BAND_NOT_CONNECTED_KEY = 'band.not_connected';

/**
 * PRD L290 (W-3): the client band view must carry BOTH notices on the same
 * screen. The PRD records a missing sync time as an acceptance failure, so the
 * two are held together as one required pair rather than two independent keys --
 * a refactor that renders one of them and forgets the other must break the
 * pairing, not silently drop a notice.
 */
const CLIENT_BAND_REQUIRED_NOTICES = Object.freeze([
  'band.w3_notice_scope',
  'band.w3_notice_sync_time',
]);

// Duplicated from the copy table so that a screen can assert presence without
// depending on the render layer's key naming.
const CLIENT_BAND_DISCLAIMER_TEXT = '手环数据为参考之一，不用于单方判定';
const CLIENT_BAND_SYNC_TIME_LABEL = '数据同步时间';

module.exports = {
  CLIENT_API_BASE_PATH,
  CLIENT_SYNC_POLL_INTERVAL_MS,
  CLIENT_BAND_DISPLAY_DAYS,
  CLIENT_BAND_NOT_CONNECTED_KEY,
  CLIENT_BAND_REQUIRED_NOTICES,
  CLIENT_BAND_DISCLAIMER_TEXT,
  CLIENT_BAND_SYNC_TIME_LABEL,
};