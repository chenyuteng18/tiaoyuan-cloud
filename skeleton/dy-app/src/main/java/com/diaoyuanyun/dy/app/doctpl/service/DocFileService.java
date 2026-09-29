package com.diaoyuanyun.dy.app.doctpl.service;

import com.diaoyuanyun.dy.app.doctpl.domain.DocTemplateRow;
import com.diaoyuanyun.dy.app.doctpl.repository.DocTemplateLedger;
import com.diaoyuanyun.dy.app.doctpl.storage.BlobStore;
import com.diaoyuanyun.dy.app.doctpl.storage.BlobStoreException;
import com.diaoyuanyun.dy.app.doctpl.storage.DocFileRef;
import com.diaoyuanyun.dy.common.crypto.Hashes;
import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;
import com.diaoyuanyun.dy.security.permission.OrgLevel;
import com.diaoyuanyun.dy.tenancy.context.TenantContext;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 文书模板的<b>文件通道</b>（B-4）—— 契约域 I 的 I3 上传 / I7 下载。
 *
 * <h2>为什么单独一个服务（不复用 {@link DocTemplateService}）</h2>
 * {@code DocTemplateController} 的类注释已登记「I3 上传 / I7 下载另立文件通道」：
 * 二者与 I1/I2/I4/I5/I6/I8 的差别不在权限，而在<b>载荷形态</b>——
 * 前者是 {@code multipart/form-data} 与 {@code 字节流}（无 JSON 结果体、有 10 MiB 上限、
 * 有对象存储往返），后者是 JSON。把文件通道混进文本服务会让"文本域的错误码语义"
 * 被"文件域的错误码语义"污染（例如 VERSION_CONFLICT 与 IDEMPOTENT_REPLAY 在这里是不同的东西）。
 *
 * <h2>I3 上传 —— 契约逐字落实（六条）</h2>
 * <ol>
 *   <li>mime_type 仅三值（pdf / docx / markdown）—— 由 {@link DocTemplateRow} 构造期把关；</li>
 *   <li>file_size ≤ 10 MiB —— 同上（{@code MAX_FILE_SIZE}）；</li>
 *   <li><b>落盘前先算 file_hash</b>（SHA-256 hex）—— 本类在写 blob 之前算，且
 *       {@link BlobStore#put} 会重算一次自校验，两次不一致直接拒收；</li>
 *   <li><b>同 {@code (tenant_id, doc_type, file_hash)} 重复上传 → 返回已有版本号</b>
 *       （幂等，错误码 4002）—— 判定走 {@code doc_template} 自身（而不是另立幂等表），
 *       因为「同 hash 已存在」这件事本身就是它的持久事实，另立表会多一处可能失同步的副本；</li>
 *   <li>🛑 <b>反病毒 / 反脚本</b>：只做字节存储 + 哈希，<b>不解析</b>（不解压 DOCX、不进宏）；</li>
 *   <li>版本 = 同 doc_type 下 max+1，<b>is_active=false</b>（新版本不自动活跃，活跃由 I6 发布独占）。</li>
 * </ol>
 *
 * <h2>🛑 第 6 条的必要性：{@code uq_doc_template_active} 会立刻顶掉</h2>
 * 部分唯一索引 {@code (tenant_id, doc_type) WHERE is_active} 只允许同 doc_type 一个活跃版本。
 * 若上传时置 {@code is_active=true}，而该 doc_type 已有活跃版本，插入会直接 23505 失败——
 * 表现为"上传成功、落库失败"的割裂。故上传一律落 false，与 {@code I4 newVersion} 同口径。
 *
 * <h2>I7 下载 —— 三重校验（契约 {@code x-super-admin-only: true}）</h2>
 * <ol>
 *   <li><b>超管</b>：{@code x-super-admin-only} ⇒ 角色须归一到总部层（见 {@link #requireSuperAdmin}）；</li>
 *   <li><b>形态</b>：仅 {@code source_type=upload} 有原件；editor 形态无 file_ref ⇒ 明确报错，
 *       不返回空流（否则"模板没有原件"与"原件是空的"无法区分）；</li>
 *   <li><b>完整性</b>：读到字节后<b>重算 SHA-256 并与登记的 file_hash 比对</b>——
 *       这正是 V5 §2.27 给 file_hash 写明的用途（「完整性校验 + 防替换」）。
 *       不符即失败：一份被替换过的原件不得被当作原件下发。</li>
 * </ol>
 *
 * <h2>🛑 写入顺序：先 blob 后落库（不是反过来）</h2>
 * 两个方向都可能在崩溃时留下中间态，但代价不对称：
 * <ul>
 *   <li>先 blob 后落库 → 崩溃留下<b>孤儿对象</b>（多余的字节，可被对账任务回收，对用户无感）；</li>
 *   <li>先落库后 blob → 崩溃留下<b>有记录无文件</b> ⇒ I7 下载 fail-closed 报错，<b>用户可见故障</b>。</li>
 * </ul>
 * 故取前者。<b>孤儿对象的回收</b>登记为后续运维事项（按 {@code doc_template} 的 file_ref 反向对账）。
 */
@Service
public class DocFileService {

    /**
     * 超管别名集合（🛑 显式列出，不默默兜底 —— 同 {@code OrgLevel} 的处置纪律）。
     *
     * <p>为什么要显式列：契约 §2.1 用 {@code hq}，骨架 {@code GateRegistry}/
     * {@code PermissionRegistry} 用大写 {@code SUPER_ADMIN}，而 PRD v1.28 的
     * {@code super_admin}（租户内最高权限）是<b>第三种写法</b>，且它<b>尚未登记在
     * {@code OrgLevel}</b> —— 即 {@code OrgLevel.fromRole("super_admin")} 会抛错。
     * 这是已被 T-11 登记的既有分叉。本类不擅自往 {@code OrgLevel} 加码
     * （那会把一处分叉改成两处），而是把"视为超管的四种写法"摆在这里，
     * 使之可被静态审计与测试断言。<b>分叉摆到明面上，好过运行时隐形。</b>
     *
     * <p>🛑 另需知道（实测事实，勿误以为四者都可达）：{@code TENANT_ADMIN} 与
     * {@code super_admin} 目前<b>未登记</b>于 {@code PermissionRegistry} 的权限码表
     * ⇒ 它们会被第一道 {@code @RequirePermission("doc:write")} 拦下（403），
     * 到不了本处的第二道判定。保留它们在本集合里的意义是：<b>当权限矩阵将来放行它们时，
     * 第二道的行为已经是对的</b>（预先正确），而不是等那时再补一个安全缺口。
     * 该现状由 {@code DocFileE2ETest} 的别名矩阵断言钉住。
     */
    static final Set<String> SUPER_ADMIN_ALIASES =
            Set.of("hq", "SUPER_ADMIN", "TENANT_ADMIN", "super_admin");

    private final DocTemplateLedger ledger;
    private final BlobStore blobStore;

    public DocFileService(DocTemplateLedger ledger, BlobStore blobStore) {
        this.ledger = ledger;
        this.blobStore = blobStore;
    }

    // ==================================================================
    // I3 · POST /doc-templates/uploads
    // ==================================================================

    /**
     * I3 上传（multipart/form-data → source_type=upload）。
     *
     * @return 落库后的模板行（source_type=upload、is_active=false）
     * @throws BizException 4002（同 hash 已存在，幂等重放）/ 1001（入参或格式非法）
     */
    public DocTemplateRow upload(String tenantId, String docType, String title,
                                 String fileName, String mimeType, byte[] bytes, String createdBy) {
        DocTemplateLedger.validateTenantId(tenantId);

        // ---- ① 形态与大小（在算出 hash 之前先做廉价校验，避免白算一次 SHA-256）----
        if (docType == null || !DocTemplateRow.DOC_TYPES.contains(docType)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "doc_type 非六值之一: " + docType);
        }
        if (mimeType == null || !DocTemplateRow.MIME_TYPES.contains(mimeType)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "mime_type 仅三值（pdf/docx/markdown）: " + mimeType);
        }
        if (bytes == null || bytes.length == 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "上传内容为空");
        }
        if (bytes.length > DocTemplateRow.MAX_FILE_SIZE) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "file_size 超上限（≤ 10 MiB）: " + bytes.length + " 字节");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "file_name 缺失");
        }
        rejectExecutablePayload(bytes);

        // ---- ② 落盘前先算 file_hash（契约逐字）----
        String fileHash = sha256(bytes);

        // ---- ③ 幂等：同 (tenant, doc_type, file_hash) → 返回已有版本号（4002）----
        Optional<DocTemplateRow> existing =
                ledger.findByDocTypeAndHash(tenantId, docType, fileHash);
        if (existing.isPresent()) {
            DocTemplateRow row = existing.get();
            throw new BizException(ErrorCode.IDEMPOTENT_REPLAY,
                    "同一文件已上传（幂等键 (tenant_id, doc_type, file_hash) 命中）"
                            + "—— 已有版本号 version=" + row.version()
                            + "，template_id=" + row.templateId());
        }

        // ---- ④ 新版本号 = 同 doc_type 下 max+1 ----
        int version = ledger.currentVersionByDocType(tenantId, docType) + 1;
        String fileRef = DocFileRef.build(tenantId, docType, version, fileHash);

        // ---- ⑤ 先 blob 后落库（顺序理由见类注释）----
        try {
            blobStore.put(fileRef, bytes, fileHash);
        } catch (BlobStoreException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "对象存储写入失败: " + e.getMessage());
        }

        DocTemplateRow row = new DocTemplateRow(
                UUID.randomUUID(), docType, title == null || title.isBlank() ? fileName : title,
                null,                                   // upload 形态：content 为 NULL
                version,
                false,                                  // 🛑 不自动活跃（见类注释第 6 条）
                "upload", fileRef, fileName, mimeType, (long) bytes.length, fileHash,
                null, "上传原件", Instant.now(), createdBy);
        ledger.insert(tenantId, row);
        return row;
    }

    // ==================================================================
    // I7 · GET /doc-templates/{id}/download
    // ==================================================================

    /** 下载结果（字节 + 元信息，供控制器直接写字节流）。 */
    public record DownloadResult(String fileName, String mimeType, long fileSize,
                                 String fileHash, byte[] bytes) {
    }

    /**
     * I7 下载原件 —— 仅超管；仅 upload 形态；读取后重算哈希校验完整性。
     *
     * @throws BizException 2001（非超管）/ 3001（模板或版本不存在）/ 5001（editor 形态无原件）
     *                      / 9001（哈希不符 —— 原件疑被替换）
     */
    public DownloadResult download(String tenantId, UUID templateId, int version) {
        DocTemplateLedger.validateTenantId(tenantId);
        requireSuperAdmin(TenantContext.role());

        DocTemplateRow row = findVersionRow(tenantId, templateId, version);
        if (!"upload".equals(row.sourceType()) || row.fileRef() == null) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                    "该版本为 editor 形态，没有原件可下载（source_type=" + row.sourceType() + "）");
        }

        byte[] bytes;
        try (java.io.InputStream in = blobStore.open(row.fileRef())) {
            bytes = in.readAllBytes();
        } catch (BlobStoreException e) {
            // fail-closed：不把"读不到"降级成空字节
            throw new BizException(ErrorCode.INTERNAL_ERROR, "原件读取失败: " + e.getMessage());
        } catch (java.io.IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "原件读取中断: " + e.getMessage());
        }

        // 完整性校验（file_hash 的设计用途：完整性 + 防替换）
        String actual = sha256(bytes);
        if (row.fileHash() == null || !actual.equalsIgnoreCase(row.fileHash())) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "原件完整性校验失败：登记 file_hash=" + row.fileHash() + "，实际=" + actual
                            + "—— 拒绝对外下发（疑似被替换）");
        }
        return new DownloadResult(row.fileName(), row.mimeType(), bytes.length, row.fileHash(), bytes);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 超管判定：角色须归一到总部层。
     *
     * <p>先用 {@link OrgLevel#tryFromRole}（对 client/GUEST 返回空而不抛错，避免把
     * "客户试图下载"报成 403 之外的东西），再对总部层放行；
     * 另接受 {@link #SUPER_ADMIN_ALIASES} 里未登记于 OrgLevel 的 {@code super_admin}。
     */
    private static void requireSuperAdmin(String role) {
        boolean isHq = OrgLevel.tryFromRole(role)
                .map(lvl -> lvl == OrgLevel.HEADQUARTERS)
                .orElse(false);
        if (!isHq && (role == null || !SUPER_ADMIN_ALIASES.contains(role))) {
            throw new BizException(ErrorCode.VISIBILITY_DENIED,
                    "I7 仅超管（x-super-admin-only）：当前角色=" + role);
        }
    }

    private DocTemplateRow findVersionRow(String tenantId, UUID templateId, int version) {
        if (templateId == null || version < 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "template_id/version 非法");
        }
        return ledger.findVersionRow(tenantId, templateId, version)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "模板版本不存在: " + templateId + " v" + version));
    }

    /**
     * 拒绝可执行载荷（🛑 <b>加固</b>，非契约原文；已在下方登记边界）。
     *
     * <p>契约 I3 的原话是「上传文件<b>不得作为可执行内容被服务端解析执行</b>」。
     * 最直接的落实是"不解压、不加载"（本类确实不做），但仅此还不够——一份 PE/ELF
     * 二进制躺在存储里，等于把风险留给下一个读它的人。故在入口直接拒收
     * <b>确定性可执行格式</b>：PE（{@code MZ}）/ ELF（{@code 0x7F 'E' 'L' 'F'}）。
     *
     * <p>🛑 刻意<b>不</b>拒 shebang（{@code #!}）：markdown 文档以 {@code #!} 开头的代码块
     * 是完全合法的内容，拒它会误伤真实业务。这是一个"宁漏勿误"的取舍，写在这里以免后人
     * 以为漏掉了。
     */
    private static void rejectExecutablePayload(byte[] bytes) {
        if (bytes.length >= 2 && bytes[0] == 0x4D && bytes[1] == 0x5A) {          // 'MZ' → PE
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "拒绝可执行载荷（PE/MZ）：契约 I3「不得作为可执行内容」");
        }
        if (bytes.length >= 4 && bytes[0] == 0x7F
                && bytes[1] == 0x45 && bytes[2] == 0x4C && bytes[3] == 0x46) {    // 0x7F 'ELF'
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "拒绝可执行载荷（ELF）：契约 I3「不得作为可执行内容」");
        }
    }

    /**
     * <b>SHA-256 hex（小写）</b> —— 本仓<b>唯一</b>的摘要口径。
     *
     * <p>🛑 可见性从包私有提升为 {@code public} 是**一次刻意的决定**，
     * 起因是 A-1（{@code agreement} 离线签署通路）：
     * {@code agreement.rendered_hash} 必须在应用层做<b>重算比对</b> ——
     * 实测库层无法验 hash（{@code pg_extension} 只有 plpgsql，pgcrypto 未装
     * ⇒ 无 {@code digest()}），故 V21 的 {@code register_agreement()} 只校验
     * hash 的<b>形态</b>（64 位 hex），把"这个 hash 确实是这份正文的摘要"
     * 这条性质的守卫留在应用层。
     *
     * <p>🛑 <b>为什么提升可见性，而不是新写一份</b>：
     * 新写一份会造出<b>第二个摘要口径</b> —— 而两份口径的差别
     * （大小写、编码、是否 trim）<b>不会报错</b>，只会在"重算比对"时
     * 表现为"同一个正文算出两个 hash"，从而把一次真实的完整性校验
     * 变成一条无法归因的红。本仓纪律：同一件事只有一处定义。
     * 本类已有的重算比对（{@code download} 里的 {@code equalsIgnoreCase}）
     * 用的是同一个方法，故复用它是"口径一致"的最直接保证。
     *
     * <p>🛑 <b>2026-09-29 A-1 收口：实现已下沉到 {@link Hashes}</b>。
     * 原先本方法是全仓唯一的摘要实现，但 agreement 域的<b>领域模型</b>
     * （{@code AgreementRecord} / {@code AgreementSnapshot}）也需要"读侧重算核验"，
     * 于是它们只能 {@code DocFileService.sha256(...)} ⇒
     * <b>domain 反向依赖 service</b> ⇒ 被架构门禁 R4 抓红（3 处）。
     * 修法是把这件能力<b>下沉到依赖方向正确的位置</b>（{@code dy-common}），
     * 而不是给 R4 开例外、也不是在 domain 里复制一份算法。
     * 本方法保留为<b>委托</b> —— 口径仍然只有一个，且既有调用点无需改动。
     *
     * <p>🛑 返回<b>小写</b> hex：与 {@code agreement.rendered_hash} 的入库口径一致
     * （V21 的 INSERT 用 {@code lower(p_rendered_hash)} 归一）。
     * 调用方做等值比较时仍需 {@code equalsIgnoreCase}（因为库里的历史行
     * 可能是升级前的写法）—— 见 {@link #sha256} 的现有用法。
     */
    public static String sha256(byte[] bytes) {
        return Hashes.sha256(bytes);
    }
}