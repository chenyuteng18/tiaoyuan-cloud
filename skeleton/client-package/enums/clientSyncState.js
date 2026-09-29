/**
 * Client-visible enum namespace.
 *
 * ADR-12 / contract L571: client-visible enums MUST carry the `client_` prefix
 * and MUST NOT share a type definition with the internal enums. The internal
 * enum that names why a day is missing lives server-side only and is scanned off
 * the client package by scan face 3.
 *
 * NOTE ON THIS COMMENT'S OWN WORDING: that internal enum is referred to here by
 * description, not by name. Earlier this comment spelled the name out to explain
 * the separation, which put the server-side identifier into the very bundle the
 * separation exists to protect -- a bare description of the enum fails the same
 * test whether it sits in code or in a comment, and a comment is not exempt. The
 * naming rule is unchanged; the bundle simply does not restate the name.
 *
 * This enum therefore has no value that carries the semantics of "the customer
 * did not wear it", and no value that carries the semantics of "not meeting the
 * bar" -- see contract L461 (conflict point C-3) and the three hard constraints
 * at contract L462.
 */
'use strict';

const CLIENT_SYNC_STATE = Object.freeze({
  SYNCING: 'syncing',
  SYNCED: 'synced',
  SYNC_FAILED: 'sync_failed',
  NO_DATA_TODAY: 'no_data_today',
});

// Enumeration of the neutral copy key that MUST accompany each state. Keeping
// the mapping here (rather than in a component) means a state can never be
// rendered without its copy, which is how a neutral state regresses into a
// negative one after a UI refactor.
const CLIENT_SYNC_STATE_COPY = Object.freeze({
  [CLIENT_SYNC_STATE.SYNCING]: 'band.syncing',
  [CLIENT_SYNC_STATE.SYNCED]: 'band.sync_ok',
  [CLIENT_SYNC_STATE.SYNC_FAILED]: 'band.sync_failed',
  [CLIENT_SYNC_STATE.NO_DATA_TODAY]: 'band.no_data_today',
});

module.exports = { CLIENT_SYNC_STATE, CLIENT_SYNC_STATE_COPY };