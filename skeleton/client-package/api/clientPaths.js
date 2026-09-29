/**
 * The client package's outbound API path list.
 *
 * contract L603 -- "disabled interface scan": the client bundle must not
 * reference a path belonging to the internal business domain.
 *
 * WHY THIS LIST IS TRANSCRIBED FROM THE CONTRACT RATHER THAN HAND-WRITTEN
 * -----------------------------------------------------------------------
 * An earlier revision of this file was curated by hand, and it had drifted.
 * Of its eight entries only two matched a frozen contract path:
 *
 *   - three were plausible but wrong names -- /customer/profile (B4 is
 *     /customers/{id}), /band/state (E6 is /customers/{id}/band/sync-status),
 *     /form/daily (D3 is /customers/{id}/daily-reports);
 *   - two existed nowhere in the contract at all -- /receipt/list and
 *     /receipt/detail (the customer's acknowledgement is delivered by WeChat
 *     subscription message, and the G5 receipt state is visible on the therapist
 *     surface only, never to the client);
 *   - one was an endpoint the contract EXPLICITLY DENIES to the client -- the
 *     server-side result endpoint at contract row E4, whose x-callable-roles is
 *     [therapist, meridian, admin] and which carries x-client-explicitly-denied.
 *
 * A hand-written allow-list is a list that rots, and a rotted allow-list is
 * worse than none: it launders a forbidden path through the very file whose job
 * is to prevent that. No word-scan would have caught E4 either, because the
 * concept word was not in the ADR-12 wordlist and a path can be wrong without
 * containing one. So the list is transcribed from the contract, and a companion
 * gate under compliance/ asserts that it stays verbatim: the set below must
 * EQUAL the set of contract paths whose x-callable-roles contains "client".
 * See compliance/README.md (section S1-6) for that gate and its evidence.
 *
 * NOTE ON THIS COMMENT'S OWN WORDING
 * ----------------------------------
 * E4 is described here by its contract row and by its visibility attribute, and
 * neither the removed path nor the concept word it contained is spelled out.
 * The removed path is not written down either. That is deliberate, not
 * squeamishness: an ADR-12 scan face fires on the concept word wherever it
 * appears in the bundle, INCLUDING IN COMMENTS, so spelling it here to
 * "document" its removal would make this file -- the file that exists to keep
 * the concept out of the bundle -- the thing that carries it in. The full
 * account of the removal, with the path named, lives under compliance/, which
 * is outside the scanned root.
 *
 * The disallowed paths are otherwise still NOT enumerated here, for the same
 * reason the original revision gave: writing them down to "block" them would
 * paste the very concept this gate exists to keep out of the bundle. The
 * disabled-interface scan keeps three independent nets:
 *   1. ADR-12 scan face 1 tokenises a line, catching an internal-domain path by
 *      its domain term;
 *   2. ADR-12 scan face 3 catches a path that spells a server-side result name;
 *   3. the companion gate under compliance/ compares this list against the
 *      contract, catching a path that is merely the WRONG NAME even when every
 *      word in it is innocuous.
 *
 * Keeping the allow-list explicit still means a path can only be reachable after
 * review -- the review simply moved to the contract, where x-callable-roles
 * already lives, instead of happening a second time (and differently) here.
 */
'use strict';

// Transcribed verbatim from contract/openapi-v1.0.0.yaml. Each entry cites its
// contract row so a reader can check it without opening the YAML; the companion
// gate verifies the citation rather than trusting it -- every row below must
// carry "client" in its x-callable-roles, or the gate reports the mismatch.
const CLIENT_ALLOWED_PATHS = Object.freeze([
  '/api/v1/auth/login',                                   // A1 登录
  '/api/v1/auth/me',                                      // A2 身份 + 可见性档位
  '/api/v1/customers/{id}',                               // B4 客户档案详情
  '/api/v1/customers/{id}/intake-profile',                // B5 建档扩展档案
  '/api/v1/scale-item-banks',                             // C1 题库
  '/api/v1/customers/{id}/assessments/{assessment_id}',   // C3 评估详情
  '/api/v1/customers/{id}/visits',                        // D2 服务记录
  '/api/v1/customers/{id}/daily-reports',                 // D3 每日填报
  '/api/v1/plans/{id}',                                   // D5 方案详情
  '/api/v1/band/sync-batches',                            // E1 同步批次
  '/api/v1/band/telemetry',                               // E2 遥测上行
  '/api/v1/customers/{id}/band/telemetry',                // E3 手环原始数据（读取）
  '/api/v1/band/available-dates',                         // E5 日期探测上报
  '/api/v1/customers/{id}/band/sync-status',              // E6 同步状态卡
]);

function assertPathAllowed(path, table) {
  const allowed = table || CLIENT_ALLOWED_PATHS;
  if (allowed.indexOf(path) === -1) {
    throw new Error('client path not allow-listed: ' + path);
  }
  return path;
}

module.exports = { CLIENT_ALLOWED_PATHS, assertPathAllowed };