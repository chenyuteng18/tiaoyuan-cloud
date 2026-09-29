/**
 * Client analytics event names.
 *
 * This is one of the two channels that silently escapes a UI-only review: the
 * page copy is changed, the event name is not, and the forbidden concept keeps
 * shipping inside the bundle's telemetry. It is therefore scanned by all three
 * ADR-12 faces.
 *
 * Event names are snake_case, describe what the customer DID, and never
 * describe an outcome or a judgement.
 */
'use strict';

const CLIENT_EVENTS = Object.freeze({
  MP_APP_LAUNCH: 'mp_app_launch',
  MP_BAND_PANEL_VIEW: 'mp_band_panel_view',
  MP_BAND_SYNC_TAP: 'mp_band_sync_tap',
  MP_BAND_SYNC_OK: 'mp_band_sync_ok',
  MP_BAND_SYNC_RETRY: 'mp_band_sync_retry',
  MP_BAND_DAY_TAP: 'mp_band_day_tap',
  MP_RECEIPT_VIEW: 'mp_receipt_view',
  MP_SUBSCRIPTION_ALLOW_TAP: 'mp_subscription_allow_tap',
  MP_DAILY_FORM_SUBMIT: 'mp_daily_form_submit',
  MP_DAILY_FORM_ABANDON: 'mp_daily_form_abandon',
  MP_PROFILE_VIEW: 'mp_profile_view',
});

// Only these properties may be attached to a client event. Anything that looks
// like a server-side judgement is deliberately absent: a computed result that
// reaches the client, even as an analytics property, has been distributed.
const CLIENT_EVENT_ALLOWED_PROPS = Object.freeze([
  'client_sync_state',
  'synced_at_day',
  'band_bound',
  'page_from',
  'attempt_no',
]);

module.exports = { CLIENT_EVENTS, CLIENT_EVENT_ALLOWED_PROPS };